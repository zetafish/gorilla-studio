//! The sequencer: owns the clock and the current graph, and runs on the audio
//! thread. Fixed-size state, no allocation, no indexing on input-derived data.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use rtrb::{Producer, PushError};

use crate::graph::{Graph, STEPS, Steps};
use crate::voice::{Ctx, Noise};

const MASTER_GAIN: f32 = 0.6;

/// Messages from the control thread, applied at the start of a buffer.
/// Indices refer to the graph the audio thread has after every earlier
/// message is applied.
#[derive(Debug, PartialEq)]
pub enum RtMsg {
    /// Replace the graph. Its carry maps say which voices and rows continue.
    SwapGraph(Box<Graph>),
    SetSteps {
        pattern: usize,
        row: usize,
        steps: Steps,
    },
    /// Play `steps` on a row from the start of `bar` (counted from the last
    /// start). The row keeps them afterwards, so if nothing else is queued the
    /// last bar keeps looping.
    ScheduleBar {
        pattern: usize,
        row: usize,
        bar: u64,
        steps: Steps,
    },
    /// Start (from step 0) or stop the clock. Starting while playing does nothing.
    Transport { playing: bool },
    /// Change tempo, keeping the position within the current step.
    SetTempo { bpm: f32 },
}

/// What the audio thread is playing, for telemetry. Only the audio thread
/// writes it; plain atomic stores, so publishing never blocks.
pub struct Playhead {
    /// Steps triggered since playback last started.
    steps: AtomicU64,
    playing: AtomicBool,
}

impl Default for Playhead {
    fn default() -> Self {
        Self {
            steps: AtomicU64::new(0),
            playing: AtomicBool::new(true),
        }
    }
}

impl Playhead {
    pub fn playing(&self) -> bool {
        self.playing.load(Ordering::Relaxed)
    }

    /// Bar and step of the most recently triggered step, counted from the
    /// last start. Before the first step it reports bar 0, step 0.
    pub fn position(&self) -> (u64, u8) {
        let steps_per_bar = STEPS as u64;
        let current = self.steps.load(Ordering::Relaxed).saturating_sub(1);
        let step = u8::try_from(current % steps_per_bar).unwrap_or(0);
        (current / steps_per_bar, step)
    }
}

pub struct Sequencer {
    graph: Box<Graph>,
    /// Old graphs go here, to be dropped off the audio thread.
    reclaim: Producer<Box<Graph>>,
    samples_per_step: f64,
    samples_until_step: f64,
    step: usize,
    steps_played: u64,
    playing: bool,
    playhead: Arc<Playhead>,
    ctx: Ctx,
}

impl Sequencer {
    pub fn new(
        sample_rate: f32,
        bpm: f32,
        playhead: Arc<Playhead>,
        graph: Box<Graph>,
        reclaim: Producer<Box<Graph>>,
    ) -> Self {
        let sample_rate = sample_rate.max(1.0);
        Self {
            graph,
            reclaim,
            samples_per_step: samples_per_step(sample_rate, bpm),
            samples_until_step: 0.0,
            step: 0,
            steps_played: 0,
            playing: true,
            playhead,
            ctx: Ctx {
                sample_rate,
                noise: Noise::new(0x9E37_79B9),
            },
        }
    }

    pub fn apply(&mut self, msg: RtMsg) {
        match msg {
            RtMsg::SwapGraph(mut graph) => {
                graph.carry_from(&self.graph);
                let old = std::mem::replace(&mut self.graph, graph);
                if let Err(PushError::Full(old)) = self.reclaim.push(old) {
                    // Never free on the audio thread. A full reclaim queue
                    // means the reclaim thread is stuck; leaking one graph is
                    // the lesser harm.
                    std::mem::forget(old);
                }
            }
            RtMsg::SetSteps {
                pattern,
                row,
                steps,
            } => {
                if let Some(row) = self.graph.row_mut(pattern, row) {
                    row.steps = steps;
                }
            }
            RtMsg::ScheduleBar {
                pattern,
                row,
                bar,
                steps,
            } => {
                // The first bar that hasn't started yet.
                let earliest = self.steps_played.div_ceil(STEPS as u64);
                if let Some(row) = self.graph.row_mut(pattern, row) {
                    row.queue.insert(bar, steps, earliest);
                }
            }
            RtMsg::Transport { playing } => self.set_playing(playing),
            RtMsg::SetTempo { bpm } => {
                let new = samples_per_step(self.ctx.sample_rate, bpm);
                // Same fraction of the step left, so the groove doesn't jump.
                self.samples_until_step *= new / self.samples_per_step;
                self.samples_per_step = new;
            }
        }
    }

    fn set_playing(&mut self, playing: bool) {
        if playing && !self.playing {
            self.step = 0;
            self.samples_until_step = 0.0;
            self.steps_played = 0;
            self.playhead.steps.store(0, Ordering::Relaxed);
            // Bar numbers start over, so queued bars no longer mean anything.
            self.graph.clear_queues();
        }
        self.playing = playing;
        self.playhead.playing.store(playing, Ordering::Relaxed);
    }

    /// Produces the next mono sample, advancing the clock by one sample while
    /// playing. Stopped, voices still ring out, so stopping never clicks.
    pub fn next_sample(&mut self) -> f32 {
        if self.playing {
            if self.samples_until_step <= 0.0 {
                self.trigger_step();
                self.samples_until_step += self.samples_per_step;
            }
            self.samples_until_step -= 1.0;
        }
        (self.graph.next_sample(&mut self.ctx) * MASTER_GAIN).tanh()
    }

    fn trigger_step(&mut self) {
        let step = self.step;
        let bar = (step == 0).then(|| self.steps_played / STEPS as u64);
        self.graph.trigger_step(step, bar);
        self.step = (step + 1) % STEPS;
        self.steps_played = self.steps_played.saturating_add(1);
        self.playhead
            .steps
            .store(self.steps_played, Ordering::Relaxed);
    }
}

/// Sixteenth notes: four steps per beat.
fn samples_per_step(sample_rate: f32, bpm: f32) -> f64 {
    let bpm = if bpm.is_finite() {
        bpm.clamp(20.0, 999.0)
    } else {
        120.0
    };
    f64::from(sample_rate) * 60.0 / f64::from(bpm) / 4.0
}

#[cfg(test)]
mod tests {
    use rtrb::{Consumer, RingBuffer};

    use super::*;
    use crate::model::{DEFAULT_HAT, Model};
    use crate::samples::SampleCache;

    const SR: f32 = 48_000.0;
    const BAR: usize = 16 * 6_000;
    const NONE: Steps = [false; STEPS];
    const ALL: Steps = [true; STEPS];
    const HAT: (usize, usize) = (0, 2);

    fn drums() -> Box<Graph> {
        Box::new(
            Model::default_drums()
                .build(&Model::default(), SR, &mut SampleCache::default())
                .unwrap_or_default(),
        )
    }

    fn sequencer_with(playhead: Arc<Playhead>) -> (Sequencer, Consumer<Box<Graph>>) {
        let (reclaim, old_graphs) = RingBuffer::new(4);
        (
            Sequencer::new(SR, 120.0, playhead, drums(), reclaim),
            old_graphs,
        )
    }

    fn sequencer() -> Sequencer {
        sequencer_with(Arc::default()).0
    }

    fn render(seq: &mut Sequencer, frames: usize) -> Vec<f32> {
        let mut out = vec![0.0; frames];
        assert_no_alloc::assert_no_alloc(|| {
            for s in &mut out {
                *s = seq.next_sample();
            }
        });
        out
    }

    fn peak(samples: &[f32]) -> f32 {
        samples.iter().fold(0.0_f32, |m, s| m.max(s.abs()))
    }

    fn set_all(seq: &mut Sequencer, steps: Steps) {
        for row in 0..3 {
            seq.apply(RtMsg::SetSteps {
                pattern: 0,
                row,
                steps,
            });
        }
    }

    fn hats(seq: &Sequencer) -> Steps {
        seq.graph
            .row(HAT.0, HAT.1)
            .map(|r| r.steps)
            .unwrap_or_default()
    }

    fn schedule_hat(seq: &mut Sequencer, bar: u64, steps: Steps) {
        seq.apply(RtMsg::ScheduleBar {
            pattern: HAT.0,
            row: HAT.1,
            bar,
            steps,
        });
    }

    #[test]
    fn default_pattern_is_audible_and_bounded() {
        let mut seq = sequencer();
        let out = render(&mut seq, SR as usize);
        assert!(out.iter().all(|s| s.is_finite() && s.abs() <= 1.0));
        assert!(peak(&out) > 0.1);
    }

    #[test]
    fn empty_pattern_is_silent() {
        let mut seq = sequencer();
        set_all(&mut seq, NONE);
        assert_eq!(peak(&render(&mut seq, SR as usize)), 0.0);
    }

    #[test]
    fn steps_land_on_the_sample_grid() {
        // At 120 BPM a sixteenth is 6000 samples at 48 kHz. Only a hat on step 1
        // means silence for the first step and sound right after it.
        let mut seq = sequencer();
        set_all(&mut seq, NONE);
        let mut hat_on_one = NONE;
        if let Some(s) = hat_on_one.get_mut(1) {
            *s = true;
        }
        seq.apply(RtMsg::SetSteps {
            pattern: HAT.0,
            row: HAT.1,
            steps: hat_on_one,
        });
        let out = render(&mut seq, 6_100);
        let (first, rest) = out.split_at(6_000);
        assert_eq!(peak(first), 0.0);
        assert!(peak(rest) > 0.0);
    }

    #[test]
    fn stop_lets_voices_ring_out_then_goes_silent() {
        let mut seq = sequencer();
        render(&mut seq, 100);
        seq.apply(RtMsg::Transport { playing: false });
        // The kick's 0.25 s decay needs about 2.3 s to drop below the gate.
        let out = render(&mut seq, 3 * SR as usize);
        let (tail, rest) = out.split_at(100);
        assert!(peak(tail) > 0.0, "a ringing kick fades rather than cuts");
        assert_eq!(peak(rest.split_at(rest.len() - 1_000).1), 0.0);
    }

    #[test]
    fn play_restarts_from_step_zero() {
        let mut seq = sequencer();
        seq.apply(RtMsg::Transport { playing: false });
        render(&mut seq, 2 * SR as usize);
        assert_eq!(seq.step, 0);
        seq.apply(RtMsg::Transport { playing: true });
        render(&mut seq, 1);
        assert_eq!(seq.step, 1, "step 0 fired on the first sample after play");
        render(&mut seq, 6_000);
        seq.apply(RtMsg::Transport { playing: true });
        assert_eq!(seq.step, 2, "play while playing keeps going");
    }

    #[test]
    fn playhead_follows_the_clock() {
        let playhead = Arc::new(Playhead::default());
        let (mut seq, _old) = sequencer_with(Arc::clone(&playhead));
        assert_eq!(playhead.position(), (0, 0));
        // 17 steps of 6000 samples: the 18th step (bar 1, step 1) has fired.
        render(&mut seq, 17 * 6_000 + 1);
        assert_eq!(playhead.position(), (1, 1));
        seq.apply(RtMsg::Transport { playing: false });
        render(&mut seq, 1);
        assert!(!playhead.playing());
        seq.apply(RtMsg::Transport { playing: true });
        render(&mut seq, 1);
        assert!(playhead.playing());
        assert_eq!(playhead.position(), (0, 0));
    }

    #[test]
    fn scheduled_bars_start_on_the_bar_line_and_then_loop() {
        let mut seq = sequencer();
        schedule_hat(&mut seq, 1, ALL);
        schedule_hat(&mut seq, 2, NONE);
        render(&mut seq, BAR);
        assert_eq!(hats(&seq), DEFAULT_HAT, "bar 0 plays the row as it was");
        render(&mut seq, 1);
        assert_eq!(hats(&seq), ALL, "bar 1 switches on its first sample");
        render(&mut seq, BAR);
        assert_eq!(hats(&seq), NONE);
        render(&mut seq, 3 * BAR);
        assert_eq!(hats(&seq), NONE, "with nothing queued, the last bar loops");
    }

    #[test]
    fn rescheduling_a_bar_replaces_it_and_late_bars_are_ignored() {
        let mut seq = sequencer();
        render(&mut seq, BAR + 1); // bar 1 has started
        schedule_hat(&mut seq, 1, ALL);
        schedule_hat(&mut seq, 2, ALL);
        schedule_hat(&mut seq, 2, NONE);
        assert_eq!(hats(&seq), DEFAULT_HAT);
        render(&mut seq, BAR);
        assert_eq!(hats(&seq), NONE);
    }

    #[test]
    fn play_clears_the_queue() {
        let mut seq = sequencer();
        schedule_hat(&mut seq, 1, ALL);
        seq.apply(RtMsg::Transport { playing: false });
        seq.apply(RtMsg::Transport { playing: true });
        render(&mut seq, 2 * BAR);
        assert_eq!(hats(&seq), DEFAULT_HAT);
    }

    #[test]
    fn graph_swaps_keep_ringing_voices_and_row_state() {
        let (mut seq, mut old_graphs) = sequencer_with(Arc::default());
        schedule_hat(&mut seq, 1, ALL);
        render(&mut seq, 10);
        let before = seq.graph.channels.first().map(|c| c.voice);

        let prev = Model::default_drums();
        let rebuilt = Box::new(
            prev.build(&prev, SR, &mut SampleCache::default())
                .unwrap_or_default(),
        );
        assert_no_alloc::assert_no_alloc(|| seq.apply(RtMsg::SwapGraph(rebuilt)));

        assert_eq!(
            seq.graph.channels.first().map(|c| c.voice),
            before,
            "kick still ringing"
        );
        assert_eq!(
            seq.graph.row(HAT.0, HAT.1).map(|r| r.queue.bars()),
            Some(vec![1])
        );
        assert!(
            old_graphs.pop().is_ok(),
            "the old graph went to the reclaim queue"
        );
    }

    #[test]
    fn tempo_changes_keep_the_position_in_the_step() {
        let mut seq = sequencer();
        render(&mut seq, 3_000); // halfway through step 0 at 120 BPM
        seq.apply(RtMsg::SetTempo { bpm: 60.0 });
        assert_eq!(seq.samples_per_step, 12_000.0);
        assert_eq!(seq.samples_until_step, 6_000.0, "still halfway");
        render(&mut seq, 6_000);
        assert_eq!(seq.step, 1);
        render(&mut seq, 1);
        assert_eq!(
            seq.step, 2,
            "step 1 fired exactly one 60 BPM half-step later"
        );
    }

    #[test]
    fn odd_inputs_do_not_break_the_clock() {
        for (sr, bpm) in [
            (0.0, 120.0),
            (SR, 0.0),
            (SR, f32::MAX),
            (1.0, 1.0),
            (SR, f32::NAN),
        ] {
            let (reclaim, _old) = RingBuffer::new(1);
            let mut seq = Sequencer::new(sr, bpm, Arc::default(), drums(), reclaim);
            assert!(render(&mut seq, 1_000).iter().all(|s| s.is_finite()));
        }
        let (reclaim, _old) = RingBuffer::new(1);
        let mut empty = Sequencer::new(SR, 120.0, Arc::default(), Box::default(), reclaim);
        empty.apply(RtMsg::SetSteps {
            pattern: 9,
            row: 9,
            steps: ALL,
        });
        assert_eq!(peak(&render(&mut empty, 10_000)), 0.0);
    }
}
