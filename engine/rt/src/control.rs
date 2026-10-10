//! Control thread: receives patches from the brain over a loopback socket,
//! validates them and hands them to the audio thread through a ring buffer.
//!
//! The audio thread drains the ring buffer at the start of each buffer, so every
//! patch lands on a buffer boundary. An envelope is applied whole or not at all.

use std::io::{self, Read};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use gorilla_proto::{Envelope, HEADER_BYTES, Patch, ProtoError};
use rtrb::Producer;

use crate::seq::{Row, RtMsg, Steps};

/// The one pattern the engine knows until it plays real songs.
const DRUMS: &str = "drums";

/// Engine state the control thread reports on.
#[derive(Default)]
pub struct Status {
    /// Epoch of the last envelope handed to the audio thread.
    pub epoch: AtomicU64,
}

/// Accepts one brain connection at a time, forever. A brain that disconnects
/// or sends garbage never affects playback.
pub fn serve(listener: TcpListener, mut tx: Producer<RtMsg>, status: Arc<Status>) {
    for conn in listener.incoming() {
        match conn {
            Ok(stream) => {
                let peer = stream
                    .peer_addr()
                    .map_or_else(|_| "unknown".to_string(), |a| a.to_string());
                eprintln!("gorilla-engine: brain connected from {peer}");
                match handle(stream, &mut tx, &status) {
                    Ok(()) => eprintln!("gorilla-engine: brain disconnected"),
                    Err(err) => eprintln!("gorilla-engine: brain connection dropped: {err}"),
                }
            }
            Err(err) => eprintln!("gorilla-engine: accept failed: {err}"),
        }
    }
}

fn handle(mut stream: TcpStream, tx: &mut Producer<RtMsg>, status: &Status) -> io::Result<()> {
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
        match apply(&payload, tx) {
            Ok(envelope) => {
                status.epoch.store(envelope.epoch, Ordering::Release);
            }
            Err(err) => eprintln!("gorilla-engine: rejected patch: {err}"),
        }
    }
}

/// Decodes and validates a payload, then queues all of its ops, or none.
fn apply(payload: &[u8], tx: &mut Producer<RtMsg>) -> Result<Envelope, String> {
    let envelope = gorilla_proto::decode(payload).map_err(|e: ProtoError| e.to_string())?;
    let msgs = envelope
        .ops
        .iter()
        .map(translate)
        .collect::<Result<Vec<_>, _>>()?;
    if tx.slots() < msgs.len() {
        return Err(format!(
            "audio thread busy: {} ops, {} free slots",
            msgs.len(),
            tx.slots()
        ));
    }
    for msg in msgs {
        tx.push(msg)
            .map_err(|_| "ring buffer filled while queueing".to_string())?;
    }
    Ok(envelope)
}

fn translate(patch: &Patch) -> Result<RtMsg, String> {
    match patch {
        Patch::SetSteps {
            pattern,
            row,
            steps,
        } => {
            if pattern != DRUMS {
                return Err(format!("unknown pattern {pattern:?}"));
            }
            let row = match row.as_str() {
                "kick" => Row::Kick,
                "snare" => Row::Snare,
                "hat" => Row::Hat,
                other => return Err(format!("unknown row {other:?}")),
            };
            let steps: Steps = steps.map(|s| s.0);
            Ok(RtMsg::SetSteps { row, steps })
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;
    use std::net::SocketAddr;
    use std::time::{Duration, Instant};

    use gorilla_proto::{PATTERN_STEPS, Step};
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

    /// Starts a control server on a free port. `None` if the OS refuses.
    fn start() -> Option<(SocketAddr, Consumer<RtMsg>, Arc<Status>)> {
        let listener = TcpListener::bind("127.0.0.1:0").ok()?;
        let addr = listener.local_addr().ok()?;
        let (tx, rx) = RingBuffer::new(8);
        let status = Arc::new(Status::default());
        let server_status = Arc::clone(&status);
        std::thread::spawn(move || serve(listener, tx, server_status));
        Some((addr, rx, status))
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

    const ALL_HATS: RtMsg = RtMsg::SetSteps {
        row: Row::Hat,
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
        bytes.extend(frame(&set_steps("bass", "hat", 2)));
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
    fn a_full_ring_rejects_the_whole_envelope() {
        let (mut tx, mut rx) = RingBuffer::new(1);
        let mut envelope = set_steps("drums", "kick", 9);
        envelope.ops.extend(set_steps("drums", "hat", 9).ops);
        let bytes = frame(&envelope);
        let payload = bytes.get(HEADER_BYTES..).unwrap_or_default();
        assert!(apply(payload, &mut tx).is_err());
        assert!(rx.pop().is_err());
    }
}
