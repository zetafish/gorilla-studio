//! Step sequencer and the built-in drum voices.
//!
//! Everything here runs on the audio thread: fixed-size state, no allocation,
//! no indexing on input-derived data.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

pub const STEPS: usize = 16;
pub const ROW_COUNT: usize = 3;

/// A row of the hard-coded drum pattern.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Row {
    Kick,
    Snare,
    Hat,
}

impl Row {
    pub const ALL: [Row; ROW_COUNT] = [Row::Kick, Row::Snare, Row::Hat];
}

pub type Steps = [bool; STEPS];

/// Messages from the control thread. `Copy` and fixed-size, so they cross the
/// ring buffer without allocating.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RtMsg {
    SetSteps {
        row: Row,
        steps: Steps,
    },
    /// Start (from step 0) or stop the clock. Starting while playing does nothing.
    Transport {
        playing: bool,
    },
    /// Play `steps` on `row` from the start of `bar` (counted from the last
    /// start). The row keeps them afterwards, so if nothing else is queued the
    /// last bar keeps looping.
    ScheduleBar {
        row: Row,
        bar: u64,
        steps: Steps,
    },
}

/// Upcoming bars a row can hold. More than the brain's lookahead needs.
pub const QUEUED_BARS: usize = 4;

/// Fixed-size queue of upcoming bars for one row.
#[derive(Clone, Copy, Default)]
struct BarQueue([Option<(u64, Steps)>; QUEUED_BARS]);

impl BarQueue {
    /// Queues `steps` for `bar`, replacing any entry for the same bar and
    /// dropping entries before `earliest`. When full, the furthest bar gives
    /// way to a nearer one.
    fn insert(&mut self, bar: u64, steps: Steps, earliest: u64) {
        if bar < earliest {
            return;
        }
        for slot in &mut self.0 {
            if slot.is_some_and(|(b, _)| b < earliest) {
                *slot = None;
            }
        }
        if let Some(slot) = self.0.iter_mut().find(|s| s.is_some_and(|(b, _)| b == bar)) {
            *slot = Some((bar, steps));
        } else if let Some(slot) = self.0.iter_mut().find(|s| s.is_none()) {
            *slot = Some((bar, steps));
        } else if let Some(slot) = self
            .0
            .iter_mut()
            .max_by_key(|s| s.map_or(0, |(b, _)| b))
            .filter(|s| s.is_some_and(|(b, _)| b > bar))
        {
            *slot = Some((bar, steps));
        }
    }

    /// Removes and returns the steps for `bar`, dropping anything older.
    fn take(&mut self, bar: u64) -> Option<Steps> {
        let mut found = None;
        for slot in &mut self.0 {
            match *slot {
                Some((b, steps)) if b == bar => {
                    found = Some(steps);
                    *slot = None;
                }
                Some((b, _)) if b < bar => *slot = None,
                _ => {}
            }
        }
        found
    }

    fn clear(&mut self) {
        self.0 = [None; QUEUED_BARS];
    }
}

const X: bool = true;
const O: bool = false;

/// The pattern the engine plays on startup, before any brain connects.
/// Rows are in `Row::ALL` order.
pub const DEFAULT_PATTERN: [Steps; ROW_COUNT] = [
    [X, O, O, O, X, O, O, O, X, O, O, O, X, O, O, O],
    [O, O, O, O, X, O, O, O, O, O, O, O, X, O, O, O],
    [X, O, X, O, X, O, X, O, X, O, X, O, X, O, X, O],
];

const MASTER_GAIN: f32 = 0.6;

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
    pattern: [Steps; ROW_COUNT],
    queued: [BarQueue; ROW_COUNT],
    voices: [Voice; ROW_COUNT],
    samples_per_step: f64,
    samples_until_step: f64,
    step: usize,
    steps_played: u64,
    playing: bool,
    playhead: Arc<Playhead>,
    noise: Noise,
}

impl Sequencer {
    pub fn new(sample_rate: f32, bpm: f32, playhead: Arc<Playhead>) -> Self {
        let sample_rate = sample_rate.max(1.0);
        let bpm = bpm.clamp(20.0, 999.0);
        Self {
            pattern: DEFAULT_PATTERN,
            queued: [BarQueue::default(); ROW_COUNT],
            voices: Row::ALL.map(|row| Voice::new(row, sample_rate)),
            // Sixteenth notes: four steps per beat.
            samples_per_step: f64::from(sample_rate) * 60.0 / f64::from(bpm) / 4.0,
            samples_until_step: 0.0,
            step: 0,
            steps_played: 0,
            playing: true,
            playhead,
            noise: Noise::new(0x9E37_79B9),
        }
    }

    pub fn apply(&mut self, msg: RtMsg) {
        match msg {
            RtMsg::SetSteps { row, steps } => self.set_steps(row, steps),
            RtMsg::Transport { playing } => self.set_playing(playing),
            RtMsg::ScheduleBar { row, bar, steps } => {
                // The first bar that hasn't started yet.
                let earliest = self.steps_played.div_ceil(STEPS as u64);
                if let Some(queue) = self.queue_mut(row) {
                    queue.insert(bar, steps, earliest);
                }
            }
        }
    }

    fn queue_mut(&mut self, row: Row) -> Option<&mut BarQueue> {
        Row::ALL
            .iter()
            .zip(self.queued.iter_mut())
            .find(|(r, _)| **r == row)
            .map(|(_, q)| q)
    }

    fn set_playing(&mut self, playing: bool) {
        if playing && !self.playing {
            self.step = 0;
            self.samples_until_step = 0.0;
            self.steps_played = 0;
            self.playhead.steps.store(0, Ordering::Relaxed);
            // Bar numbers start over, so queued bars no longer mean anything.
            for queue in &mut self.queued {
                queue.clear();
            }
        }
        self.playing = playing;
        self.playhead.playing.store(playing, Ordering::Relaxed);
    }

    pub fn set_steps(&mut self, row: Row, steps: Steps) {
        if let Some((_, slot)) = Row::ALL
            .iter()
            .zip(self.pattern.iter_mut())
            .find(|(r, _)| **r == row)
        {
            *slot = steps;
        }
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

        let mut mix = 0.0;
        for voice in &mut self.voices {
            mix += voice.next(&mut self.noise);
        }
        (mix * MASTER_GAIN).tanh()
    }

    fn trigger_step(&mut self) {
        let step = self.step;
        if step == 0 {
            let bar = self.steps_played / STEPS as u64;
            for (row, queue) in self.pattern.iter_mut().zip(self.queued.iter_mut()) {
                if let Some(steps) = queue.take(bar) {
                    *row = steps;
                }
            }
        }
        for (row, voice) in self.pattern.iter().zip(self.voices.iter_mut()) {
            if row.get(step).copied().unwrap_or(false) {
                voice.trigger();
            }
        }
        self.step = (step + 1) % STEPS;
        self.steps_played = self.steps_played.saturating_add(1);
        self.playhead
            .steps
            .store(self.steps_played, Ordering::Relaxed);
    }
}

/// One synthesized drum. Envelopes decay by a per-sample factor, so a voice
/// costs a few multiplies per sample and never allocates.
struct Voice {
    row: Row,
    sample_rate: f32,
    amp: f32,
    amp_decay: f32,
    pitch_env: f32,
    pitch_decay: f32,
    phase: f32,
    lowpass: f32,
}

impl Voice {
    fn new(row: Row, sample_rate: f32) -> Self {
        let decay = |seconds: f32| (-1.0 / (seconds * sample_rate)).exp();
        let (amp_decay, pitch_decay) = match row {
            Row::Kick => (decay(0.25), decay(0.03)),
            Row::Snare => (decay(0.12), decay(0.02)),
            Row::Hat => (decay(0.035), 0.0),
        };
        Self {
            row,
            sample_rate,
            amp: 0.0,
            amp_decay,
            pitch_env: 0.0,
            pitch_decay,
            phase: 0.0,
            lowpass: 0.0,
        }
    }

    fn trigger(&mut self) {
        self.amp = 1.0;
        self.pitch_env = 1.0;
        self.phase = 0.0;
    }

    fn next(&mut self, noise: &mut Noise) -> f32 {
        if self.amp < 1.0e-4 {
            self.amp = 0.0;
            return 0.0;
        }
        let out = match self.row {
            Row::Kick => {
                let freq = 50.0 + 110.0 * self.pitch_env;
                self.sine(freq)
            }
            Row::Snare => {
                let tone = self.sine(180.0 + 40.0 * self.pitch_env);
                0.35 * tone + 0.65 * self.highpass(noise.next(), 0.3)
            }
            Row::Hat => 0.45 * self.highpass(noise.next(), 0.7),
        };
        let out = out * self.amp;
        self.amp *= self.amp_decay;
        self.pitch_env *= self.pitch_decay;
        out
    }

    fn sine(&mut self, freq: f32) -> f32 {
        self.phase = (self.phase + freq / self.sample_rate).fract();
        (self.phase * std::f32::consts::TAU).sin()
    }

    /// Input minus a one-pole lowpass; `coeff` closer to 1 cuts more lows.
    fn highpass(&mut self, input: f32, coeff: f32) -> f32 {
        self.lowpass += (1.0 - coeff) * (input - self.lowpass);
        input - self.lowpass
    }
}

/// xorshift32 white noise in [-1, 1).
struct Noise(u32);

impl Noise {
    fn new(seed: u32) -> Self {
        Self(seed.max(1))
    }

    fn next(&mut self) -> f32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        (x as f32 / u32::MAX as f32) * 2.0 - 1.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: f32 = 48_000.0;

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

    #[test]
    fn default_pattern_is_audible_and_bounded() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        let out = render(&mut seq, SR as usize);
        assert!(out.iter().all(|s| s.is_finite() && s.abs() <= 1.0));
        assert!(peak(&out) > 0.1);
    }

    #[test]
    fn empty_pattern_is_silent() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        for row in Row::ALL {
            seq.set_steps(row, [false; STEPS]);
        }
        assert_eq!(peak(&render(&mut seq, SR as usize)), 0.0);
    }

    #[test]
    fn steps_land_on_the_sample_grid() {
        // At 120 BPM a sixteenth is 6000 samples at 48 kHz. Only a hat on step 1
        // means silence for the first step and sound right after it.
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        let hat_on_one = [O, X, O, O, O, O, O, O, O, O, O, O, O, O, O, O];
        for row in Row::ALL {
            seq.set_steps(row, [false; STEPS]);
        }
        seq.set_steps(Row::Hat, hat_on_one);
        let out = render(&mut seq, 6_100);
        let (first, rest) = out.split_at(6_000);
        assert_eq!(peak(first), 0.0);
        assert!(peak(rest) > 0.0);
    }

    #[test]
    fn apply_replaces_a_row() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        for row in Row::ALL {
            seq.apply(RtMsg::SetSteps {
                row,
                steps: [false; STEPS],
            });
        }
        assert_eq!(seq.pattern, [[false; STEPS]; ROW_COUNT]);
        seq.apply(RtMsg::SetSteps {
            row: Row::Snare,
            steps: [true; STEPS],
        });
        assert_eq!(seq.pattern, [[false; STEPS], [true; STEPS], [false; STEPS]]);
    }

    #[test]
    fn stop_lets_voices_ring_out_then_goes_silent() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
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
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        seq.apply(RtMsg::Transport { playing: false });
        render(&mut seq, 2 * SR as usize);
        render(&mut seq, 1);
        assert_eq!(seq.step, 0);
        seq.apply(RtMsg::Transport { playing: true });
        render(&mut seq, 1);
        assert_eq!(seq.step, 1, "step 0 fired on the first sample after play");
        // Play while playing keeps going.
        render(&mut seq, 6_000);
        seq.apply(RtMsg::Transport { playing: true });
        assert_eq!(seq.step, 2);
    }

    #[test]
    fn playhead_follows_the_clock() {
        let playhead = Arc::new(Playhead::default());
        let mut seq = Sequencer::new(SR, 120.0, Arc::clone(&playhead));
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

    const BAR: usize = 16 * 6_000;
    const NONE: Steps = [false; STEPS];
    const ALL: Steps = [true; STEPS];

    fn hats(seq: &Sequencer) -> Steps {
        seq.pattern.get(2).copied().unwrap_or_default()
    }

    #[test]
    fn scheduled_bars_start_on_the_bar_line_and_then_loop() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        seq.apply(RtMsg::ScheduleBar {
            row: Row::Hat,
            bar: 1,
            steps: ALL,
        });
        seq.apply(RtMsg::ScheduleBar {
            row: Row::Hat,
            bar: 2,
            steps: NONE,
        });
        render(&mut seq, BAR);
        assert_eq!(hats(&seq), HAT_DEFAULT, "bar 0 plays the row as it was");
        render(&mut seq, 1);
        assert_eq!(hats(&seq), ALL, "bar 1 switches on its first sample");
        render(&mut seq, BAR);
        assert_eq!(hats(&seq), NONE);
        render(&mut seq, 3 * BAR);
        assert_eq!(hats(&seq), NONE, "with nothing queued, the last bar loops");
    }

    #[test]
    fn rescheduling_a_bar_replaces_it_and_late_bars_are_ignored() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        render(&mut seq, BAR + 1); // bar 1 has started
        for (bar, steps) in [(1, ALL), (2, ALL), (2, NONE)] {
            seq.apply(RtMsg::ScheduleBar {
                row: Row::Hat,
                bar,
                steps,
            });
        }
        assert_eq!(hats(&seq), HAT_DEFAULT);
        render(&mut seq, BAR);
        assert_eq!(hats(&seq), NONE);
    }

    #[test]
    fn a_full_queue_keeps_the_nearest_bars() {
        let mut queue = BarQueue::default();
        for bar in [10, 9, 8, 7, 6, 5] {
            queue.insert(bar, ALL, 0);
        }
        let mut bars: Vec<u64> = queue.0.iter().flatten().map(|(b, _)| *b).collect();
        bars.sort_unstable();
        assert_eq!(bars, [5, 6, 7, 8]);
        assert_eq!(queue.take(7), Some(ALL));
        assert_eq!(queue.0.iter().flatten().count(), 1, "5 and 6 were stale");
    }

    #[test]
    fn play_clears_the_queue() {
        let mut seq = Sequencer::new(SR, 120.0, Arc::default());
        seq.apply(RtMsg::ScheduleBar {
            row: Row::Hat,
            bar: 1,
            steps: ALL,
        });
        seq.apply(RtMsg::Transport { playing: false });
        seq.apply(RtMsg::Transport { playing: true });
        render(&mut seq, 2 * BAR);
        assert_eq!(hats(&seq), HAT_DEFAULT);
    }

    const HAT_DEFAULT: Steps = [X, O, X, O, X, O, X, O, X, O, X, O, X, O, X, O];

    #[test]
    fn odd_inputs_do_not_break_the_clock() {
        for (sr, bpm) in [(0.0, 120.0), (SR, 0.0), (SR, f32::MAX), (1.0, 1.0)] {
            let mut seq = Sequencer::new(sr, bpm, Arc::default());
            assert!(render(&mut seq, 1_000).iter().all(|s| s.is_finite()));
        }
    }
}
