//! Step sequencer and the built-in drum voices.
//!
//! Everything here runs on the audio thread: fixed-size state, no allocation,
//! no indexing on input-derived data.

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

pub struct Sequencer {
    pattern: [Steps; ROW_COUNT],
    voices: [Voice; ROW_COUNT],
    samples_per_step: f64,
    samples_until_step: f64,
    step: usize,
    playing: bool,
    noise: Noise,
}

impl Sequencer {
    pub fn new(sample_rate: f32, bpm: f32) -> Self {
        let sample_rate = sample_rate.max(1.0);
        let bpm = bpm.clamp(20.0, 999.0);
        Self {
            pattern: DEFAULT_PATTERN,
            voices: Row::ALL.map(|row| Voice::new(row, sample_rate)),
            // Sixteenth notes: four steps per beat.
            samples_per_step: f64::from(sample_rate) * 60.0 / f64::from(bpm) / 4.0,
            samples_until_step: 0.0,
            step: 0,
            playing: true,
            noise: Noise::new(0x9E37_79B9),
        }
    }

    pub fn apply(&mut self, msg: RtMsg) {
        match msg {
            RtMsg::SetSteps { row, steps } => self.set_steps(row, steps),
            RtMsg::Transport { playing } => self.set_playing(playing),
        }
    }

    fn set_playing(&mut self, playing: bool) {
        if playing && !self.playing {
            self.step = 0;
            self.samples_until_step = 0.0;
        }
        self.playing = playing;
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
        for (row, voice) in self.pattern.iter().zip(self.voices.iter_mut()) {
            if row.get(step).copied().unwrap_or(false) {
                voice.trigger();
            }
        }
        self.step = (step + 1) % STEPS;
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
        let mut seq = Sequencer::new(SR, 120.0);
        let out = render(&mut seq, SR as usize);
        assert!(out.iter().all(|s| s.is_finite() && s.abs() <= 1.0));
        assert!(peak(&out) > 0.1);
    }

    #[test]
    fn empty_pattern_is_silent() {
        let mut seq = Sequencer::new(SR, 120.0);
        for row in Row::ALL {
            seq.set_steps(row, [false; STEPS]);
        }
        assert_eq!(peak(&render(&mut seq, SR as usize)), 0.0);
    }

    #[test]
    fn steps_land_on_the_sample_grid() {
        // At 120 BPM a sixteenth is 6000 samples at 48 kHz. Only a hat on step 1
        // means silence for the first step and sound right after it.
        let mut seq = Sequencer::new(SR, 120.0);
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
        let mut seq = Sequencer::new(SR, 120.0);
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
        let mut seq = Sequencer::new(SR, 120.0);
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
        let mut seq = Sequencer::new(SR, 120.0);
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
    fn odd_inputs_do_not_break_the_clock() {
        for (sr, bpm) in [(0.0, 120.0), (SR, 0.0), (SR, f32::MAX), (1.0, 1.0)] {
            let mut seq = Sequencer::new(sr, bpm);
            assert!(render(&mut seq, 1_000).iter().all(|s| s.is_finite()));
        }
    }
}
