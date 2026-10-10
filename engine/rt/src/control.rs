//! Control thread: receives patches from the brain over a loopback socket,
//! validates them and hands them to the audio thread through a ring buffer.
//!
//! The audio thread drains the ring buffer at the start of each buffer, so every
//! patch lands on a buffer boundary. An envelope is applied whole or not at all.
//!
//! Each connection also gets a telemetry thread writing back to the brain: a
//! hello first, then the playhead about 60 times a second.

use std::hash::{BuildHasher, Hasher};
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use gorilla_proto::{Envelope, HEADER_BYTES, Patch, ProtoError, Telemetry};
use rtrb::Producer;

use crate::graph::Steps;
use crate::model::{GenSpec, Model, steps_from};
use crate::samples::SampleCache;
use crate::seq::{Playhead, RtMsg};

/// About 60 Hz.
const TELEMETRY_INTERVAL: Duration = Duration::from_millis(16);

/// Engine state the control and telemetry threads share.
pub struct Status {
    /// Random per engine process; lets the brain spot a restarted engine.
    pub engine_id: u32,
    /// Epoch of the last envelope handed to the audio thread.
    pub epoch: AtomicU64,
    /// Written by the audio thread.
    pub playhead: Arc<Playhead>,
}

impl Status {
    pub fn new(playhead: Arc<Playhead>) -> Self {
        // RandomState is seeded randomly per process: enough for an instance id.
        let random = std::collections::hash_map::RandomState::new()
            .build_hasher()
            .finish();
        Self {
            engine_id: (random >> 32) as u32,
            epoch: AtomicU64::new(0),
            playhead,
        }
    }
}

/// Accepts one brain connection at a time, forever. A brain that disconnects
/// or sends garbage never affects playback.
pub fn serve(listener: TcpListener, mut control: Control, status: Arc<Status>) {
    for conn in listener.incoming() {
        match conn {
            Ok(stream) => {
                let peer = stream
                    .peer_addr()
                    .map_or_else(|_| "unknown".to_string(), |a| a.to_string());
                eprintln!("gorilla-engine: brain connected from {peer}");
                match handle(stream, &mut control, &status) {
                    Ok(()) => eprintln!("gorilla-engine: brain disconnected"),
                    Err(err) => eprintln!("gorilla-engine: brain connection dropped: {err}"),
                }
            }
            Err(err) => eprintln!("gorilla-engine: accept failed: {err}"),
        }
    }
}

fn handle(stream: TcpStream, control: &mut Control, status: &Arc<Status>) -> io::Result<()> {
    let writer = stream.try_clone()?;
    let done = Arc::new(AtomicBool::new(false));
    let telemetry = {
        let status = Arc::clone(status);
        let done = Arc::clone(&done);
        std::thread::Builder::new()
            .name("telemetry".into())
            .spawn(move || send_telemetry(writer, &status, &done))?
    };

    let result = read_patches(&stream, control, status);

    done.store(true, Ordering::Release);
    let _ = stream.shutdown(Shutdown::Both);
    // A write error there just means the brain went away; the read side
    // already reports how the connection ended.
    let _ = telemetry.join();
    result
}

/// Hello, then the playhead until the connection ends.
fn send_telemetry(mut out: TcpStream, status: &Status, done: &AtomicBool) -> io::Result<()> {
    let hello = Telemetry::Hello {
        engine_id: status.engine_id,
        epoch: status.epoch.load(Ordering::Acquire),
    };
    write_frame(&mut out, &hello)?;
    while !done.load(Ordering::Acquire) {
        let (bar, step) = status.playhead.position();
        let playhead = Telemetry::Playhead {
            epoch: status.epoch.load(Ordering::Acquire),
            playing: status.playhead.playing(),
            bar,
            step,
        };
        write_frame(&mut out, &playhead)?;
        std::thread::sleep(TELEMETRY_INTERVAL);
    }
    Ok(())
}

fn write_frame(out: &mut TcpStream, msg: &Telemetry) -> io::Result<()> {
    let frame = gorilla_proto::encode_frame(msg).map_err(io::Error::other)?;
    out.write_all(&frame)
}

fn read_patches(mut stream: &TcpStream, control: &mut Control, status: &Status) -> io::Result<()> {
    loop {
        let mut header = [0; HEADER_BYTES];
        match stream.read_exact(&mut header) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
            Err(err) => return Err(err),
        }
        // A bad length means we can't find the next frame: drop the connection.
        let len = gorilla_proto::payload_len(header).map_err(io::Error::other)?;
        let mut payload = vec![0; len];
        stream.read_exact(&mut payload)?;

        // A bad payload is rejected on its own; the stream stays in sync.
        match control.apply(&payload) {
            Ok(envelope) => {
                status.epoch.store(envelope.epoch, Ordering::Release);
            }
            Err(err) => eprintln!("gorilla-engine: rejected patch: {err}"),
        }
    }
}

/// The control thread's side of the engine: the song model and the queue to
/// the audio thread.
pub struct Control {
    model: Model,
    sample_rate: f32,
    samples: SampleCache,
    tx: Producer<RtMsg>,
}

/// An op that becomes a message once the final graph layout is known.
enum Pending<'a> {
    Steps(&'a str, &'a str, Steps),
    Bar(&'a str, &'a str, u64, Steps),
    Transport(bool),
    Tempo(f32),
}

impl Control {
    /// `model` must be what the audio thread's current graph was built from.
    pub fn new(model: Model, sample_rate: f32, tx: Producer<RtMsg>) -> Self {
        Self {
            model,
            sample_rate,
            samples: SampleCache::default(),
            tx,
        }
    }

    /// Decodes and validates a payload, then queues all of its ops, or none.
    fn apply(&mut self, payload: &[u8]) -> Result<Envelope, String> {
        let envelope = gorilla_proto::decode(payload).map_err(|e: ProtoError| e.to_string())?;
        let (next, msgs) = self.plan(&envelope.ops)?;
        if self.tx.slots() < msgs.len() {
            return Err(format!(
                "audio thread busy: {} ops, {} free slots",
                msgs.len(),
                self.tx.slots()
            ));
        }
        for msg in msgs {
            self.tx
                .push(msg)
                .map_err(|_| "ring buffer filled while queueing".to_string())?;
        }
        self.model = next;
        Ok(envelope)
    }

    /// The model after `ops`, and the messages that take the audio thread
    /// there. Changes to the model's shape become one new graph, swapped in
    /// before the other messages; nothing is committed here.
    fn plan(&mut self, ops: &[Patch]) -> Result<(Model, Vec<RtMsg>), String> {
        let mut next = self.model.clone();
        let mut reshaped = false;
        let mut pending = Vec::with_capacity(ops.len());
        for op in ops {
            match op {
                Patch::SetSteps {
                    pattern,
                    row,
                    steps,
                } => {
                    let steps = steps_from(steps);
                    if next.set_steps(pattern, row, steps) {
                        reshaped = true;
                    } else {
                        pending.push(Pending::Steps(pattern, row, steps));
                    }
                }
                Patch::ScheduleBar {
                    pattern,
                    row,
                    bar,
                    steps,
                } => pending.push(Pending::Bar(pattern, row, *bar, steps_from(steps))),
                Patch::SetTransport { playing } => pending.push(Pending::Transport(*playing)),
                Patch::RemoveRow { pattern, row } => reshaped |= next.remove_row(pattern, row),
                Patch::RemovePattern { pattern } => reshaped |= next.remove_pattern(pattern),
                Patch::SetChannel { channel, generator } => {
                    reshaped |= next.set_channel(channel, GenSpec::from_wire(generator)?);
                }
                Patch::RemoveChannel { channel } => reshaped |= next.remove_channel(channel),
                Patch::SetBpm { bpm } => {
                    if !(gorilla_proto::MIN_BPM..=gorilla_proto::MAX_BPM).contains(bpm) {
                        return Err(format!("bpm {bpm} out of range"));
                    }
                    pending.push(Pending::Tempo(*bpm as f32));
                }
                Patch::Reset => {
                    next = Model::default();
                    reshaped = true;
                }
            }
        }

        let mut msgs = Vec::with_capacity(pending.len() + 1);
        if reshaped {
            let graph = next.build(&self.model, self.sample_rate, &mut self.samples)?;
            msgs.push(RtMsg::SwapGraph(Box::new(graph)));
        }
        let row_index = |pattern: &str, row: &str| {
            next.row_index(pattern, row)
                .ok_or_else(|| format!("unknown row {row:?} in pattern {pattern:?}"))
        };
        for p in pending {
            msgs.push(match p {
                Pending::Steps(pattern, row, steps) => {
                    let (pattern, row) = row_index(pattern, row)?;
                    RtMsg::SetSteps {
                        pattern,
                        row,
                        steps,
                    }
                }
                Pending::Bar(pattern, row, bar, steps) => {
                    let (pattern, row) = row_index(pattern, row)?;
                    RtMsg::ScheduleBar {
                        pattern,
                        row,
                        bar,
                        steps,
                    }
                }
                Pending::Transport(playing) => RtMsg::Transport { playing },
                Pending::Tempo(bpm) => RtMsg::SetTempo { bpm },
            });
        }
        Ok((next, msgs))
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;
    use std::net::SocketAddr;
    use std::time::{Duration, Instant};

    use gorilla_proto::{Gen, PATTERN_STEPS, Step};
    use rtrb::{Consumer, RingBuffer};

    use super::*;

    fn set_steps(pattern: &str, row: &str, epoch: u64) -> Envelope {
        Envelope {
            epoch,
            ops: vec![Patch::SetSteps {
                pattern: pattern.into(),
                row: row.into(),
                steps: [Step(true); PATTERN_STEPS],
            }],
        }
    }

    fn frame(envelope: &Envelope) -> Vec<u8> {
        gorilla_proto::encode_frame(envelope).unwrap_or_default()
    }

    fn control(capacity: usize) -> (Control, Consumer<RtMsg>) {
        let (tx, rx) = RingBuffer::new(capacity);
        (Control::new(Model::default_drums(), SR, tx), rx)
    }

    /// Starts a control server on a free port. `None` if the OS refuses.
    fn start() -> Option<(SocketAddr, Consumer<RtMsg>, Arc<Status>)> {
        let listener = TcpListener::bind("127.0.0.1:0").ok()?;
        let addr = listener.local_addr().ok()?;
        let (control, rx) = control(8);
        let status = Arc::new(Status::new(Arc::default()));
        let server_status = Arc::clone(&status);
        std::thread::spawn(move || serve(listener, control, server_status));
        Some((addr, rx, status))
    }

    fn apply(control: &mut Control, envelope: &Envelope) -> Result<Envelope, String> {
        let bytes = frame(envelope);
        control.apply(bytes.get(HEADER_BYTES..).unwrap_or_default())
    }

    fn send(addr: SocketAddr, bytes: &[u8]) -> bool {
        TcpStream::connect(addr)
            .and_then(|mut s| s.write_all(bytes))
            .is_ok()
    }

    fn wait_for_epoch(status: &Status, epoch: u64) -> bool {
        let deadline = Instant::now() + Duration::from_secs(5);
        while Instant::now() < deadline {
            if status.epoch.load(Ordering::Acquire) == epoch {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        false
    }

    const SR: f32 = 48_000.0;

    const ALL_HATS: RtMsg = RtMsg::SetSteps {
        pattern: 0,
        row: 2,
        steps: [true; PATTERN_STEPS],
    };

    #[test]
    fn queues_valid_patches_and_skips_invalid_ones() {
        let started = start();
        assert!(started.is_some(), "could not start the control server");
        let Some((addr, mut rx, status)) = started else {
            return;
        };
        let mut bytes = frame(&set_steps("drums", "cowbell", 1));
        bytes.extend(frame(&set_steps("bass", "cowbell", 2)));
        bytes.extend(frame(&set_steps("drums", "hat", 3)));
        assert!(send(addr, &bytes));

        assert!(wait_for_epoch(&status, 3));
        assert_eq!(rx.pop(), Ok(ALL_HATS));
        assert!(
            rx.pop().is_err(),
            "rejected envelopes must not queue anything"
        );
    }

    #[test]
    fn survives_a_brain_that_sends_garbage_and_leaves() {
        let started = start();
        assert!(started.is_some(), "could not start the control server");
        let Some((addr, mut rx, status)) = started else {
            return;
        };
        // An oversized length header, then a hang-up mid-frame.
        assert!(send(addr, &[0xff; 64]));
        assert!(send(addr, &[0, 0, 0, 10, 1, 2]));
        // The next brain still gets through.
        assert!(send(addr, &frame(&set_steps("drums", "hat", 5))));
        assert!(wait_for_epoch(&status, 5));
        assert_eq!(rx.pop(), Ok(ALL_HATS));
    }

    #[test]
    fn transport_reaches_the_audio_thread_and_status() {
        let started = start();
        assert!(started.is_some(), "could not start the control server");
        let Some((addr, mut rx, status)) = started else {
            return;
        };
        let stop = Envelope {
            epoch: 4,
            ops: vec![Patch::SetTransport { playing: false }],
        };
        assert!(send(addr, &frame(&stop)));
        assert!(wait_for_epoch(&status, 4));
        assert_eq!(rx.pop(), Ok(RtMsg::Transport { playing: false }));
    }

    fn read_telemetry(stream: &mut TcpStream) -> Option<Telemetry> {
        let mut header = [0; HEADER_BYTES];
        stream.read_exact(&mut header).ok()?;
        let mut payload = vec![0; gorilla_proto::payload_len(header).ok()?];
        stream.read_exact(&mut payload).ok()?;
        gorilla_proto::decode_telemetry(&payload).ok()
    }

    #[test]
    fn says_hello_then_streams_the_playhead() {
        let started = start();
        assert!(started.is_some(), "could not start the control server");
        let Some((addr, _rx, status)) = started else {
            return;
        };
        let stream = TcpStream::connect(addr);
        assert!(stream.is_ok());
        let Ok(mut stream) = stream else {
            return;
        };
        assert_eq!(
            read_telemetry(&mut stream),
            Some(Telemetry::Hello {
                engine_id: status.engine_id,
                epoch: 0
            })
        );
        assert!(
            stream
                .write_all(&frame(&set_steps("drums", "hat", 8)))
                .is_ok()
        );
        let acked = (0..200).any(|_| {
            matches!(
                read_telemetry(&mut stream),
                Some(Telemetry::Playhead { epoch: 8, .. })
            )
        });
        assert!(acked, "playhead reports the applied epoch");
    }

    #[test]
    fn schedule_bar_reaches_the_audio_thread() {
        let (mut control, mut rx) = control(4);
        let envelope = Envelope {
            epoch: 2,
            ops: vec![Patch::ScheduleBar {
                pattern: "drums".into(),
                row: "kick".into(),
                bar: 12,
                steps: [Step(false); PATTERN_STEPS],
            }],
        };
        assert_eq!(apply(&mut control, &envelope), Ok(envelope));
        assert_eq!(
            rx.pop(),
            Ok(RtMsg::ScheduleBar {
                pattern: 0,
                row: 0,
                bar: 12,
                steps: [false; PATTERN_STEPS]
            })
        );
    }

    #[test]
    fn a_full_ring_rejects_the_whole_envelope() {
        let (mut control, mut rx) = control(1);
        let mut envelope = set_steps("drums", "kick", 9);
        envelope.ops.extend(set_steps("drums", "hat", 9).ops);
        assert!(apply(&mut control, &envelope).is_err());
        assert!(rx.pop().is_err());
    }

    #[test]
    fn new_rows_and_patterns_swap_in_a_graph_first() {
        let (mut control, mut rx) = control(8);
        let mut envelope = set_steps("drums", "kick", 1);
        envelope.ops.extend(set_steps("fills", "hat", 1).ops);
        assert!(apply(&mut control, &envelope).is_ok());

        let swap = rx.pop();
        assert!(matches!(&swap, Ok(RtMsg::SwapGraph(g)) if g.patterns.len() == 2));
        if let Ok(RtMsg::SwapGraph(graph)) = swap {
            assert_eq!(graph.row_carry.get(1), Some(&vec![None]));
            assert_eq!(graph.channel_carry, [Some(0), Some(1), Some(2)]);
        }
        assert!(matches!(
            rx.pop(),
            Ok(RtMsg::SetSteps {
                pattern: 0,
                row: 0,
                ..
            })
        ));
        assert!(rx.pop().is_err(), "the new row's steps came with the graph");
    }

    fn envelope(ops: Vec<Patch>) -> Envelope {
        Envelope { epoch: 1, ops }
    }

    #[test]
    fn a_snapshot_replaces_everything_and_keeps_shared_channels_ringing() {
        let (mut control, mut rx) = control(8);
        let clap = crate::samples::tests::write_wav("clap", 44_100, &[0, 8_000, -8_000]);
        let snapshot = envelope(vec![
            Patch::Reset,
            Patch::SetChannel {
                channel: "kick".into(),
                generator: Gen::Builtin {
                    voice: "kick".into(),
                },
            },
            Patch::SetChannel {
                channel: "clap".into(),
                generator: Gen::Sampler {
                    sample: clap.display().to_string(),
                },
            },
            Patch::SetSteps {
                pattern: "beat".into(),
                row: "clap".into(),
                steps: [Step(true); PATTERN_STEPS],
            },
            Patch::SetBpm { bpm: 90.0 },
        ]);
        assert!(apply(&mut control, &snapshot).is_ok());
        let swap = rx.pop();
        assert!(matches!(&swap, Ok(RtMsg::SwapGraph(_))));
        if let Ok(RtMsg::SwapGraph(graph)) = swap {
            assert_eq!(graph.channels.len(), 2);
            assert_eq!(graph.channel_carry, [Some(0), None], "kick keeps ringing");
            assert_eq!(graph.patterns.len(), 1);
        }
        assert_eq!(rx.pop(), Ok(RtMsg::SetTempo { bpm: 90.0 }));
        assert_eq!(control.model.channel_index("snare"), None);
    }

    #[test]
    fn bad_channels_and_tempos_are_rejected() {
        let (mut control, mut rx) = control(8);
        for op in [
            Patch::SetChannel {
                channel: "x".into(),
                generator: Gen::Builtin {
                    voice: "cowbell".into(),
                },
            },
            Patch::SetChannel {
                channel: "x".into(),
                generator: Gen::Sampler {
                    sample: "/no/such/file.wav".into(),
                },
            },
            Patch::SetBpm { bpm: 5.0 },
            Patch::SetBpm { bpm: f64::NAN },
        ] {
            assert!(apply(&mut control, &envelope(vec![op])).is_err());
        }
        assert!(rx.pop().is_err());
        assert_eq!(control.model, Model::default_drums());
    }

    #[test]
    fn removing_a_channel_takes_its_rows_with_it() {
        let (mut control, mut rx) = control(8);
        let remove = envelope(vec![Patch::RemoveChannel {
            channel: "hat".into(),
        }]);
        assert!(apply(&mut control, &remove).is_ok());
        assert!(matches!(rx.pop(), Ok(RtMsg::SwapGraph(g)) if g.channels.len() == 2));
        assert!(apply(&mut control, &set_steps("drums", "hat", 2)).is_err());
    }

    #[test]
    fn a_rejected_envelope_leaves_the_model_alone() {
        let (mut control, mut rx) = control(8);
        let mut envelope = set_steps("fills", "hat", 1);
        envelope.ops.extend(set_steps("drums", "cowbell", 1).ops);
        assert!(apply(&mut control, &envelope).is_err());
        assert!(rx.pop().is_err());
        assert_eq!(control.model, Model::default_drums());
    }
}
