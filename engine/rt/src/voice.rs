//! Sound sources. A voice's state is a few plain numbers, so it can be copied
//! from one graph to the next when the graph is swapped, and nothing here
//! allocates.

use std::sync::Arc;

use crate::samples::Sample;

/// The synthesized drums the engine was born with.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Builtin {
    Kick,
    Snare,
    Hat,
}

impl Builtin {
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "kick" => Some(Builtin::Kick),
            "snare" => Some(Builtin::Snare),
            "hat" => Some(Builtin::Hat),
            _ => None,
        }
    }
}

/// What a channel plays. Built on the control thread; read-only on the audio
/// thread.
#[derive(Debug, Clone, PartialEq)]
pub enum Generator {
    Builtin {
        kind: Builtin,
        amp_decay: f32,
        pitch_decay: f32,
    },
    /// Plays a sample once per trigger, resampled by linear interpolation.
    Sampler {
        sample: Arc<Sample>,
        /// Sample frames per output frame.
        step: f64,
    },
}

impl Generator {
    pub fn sampler(sample: Arc<Sample>, sample_rate: f32) -> Self {
        let step = f64::from(sample.rate) / f64::from(sample_rate.max(1.0));
        Generator::Sampler { sample, step }
    }

    pub fn builtin(kind: Builtin, sample_rate: f32) -> Self {
        let decay = |seconds: f32| (-1.0 / (seconds * sample_rate.max(1.0))).exp();
        let (amp_decay, pitch_decay) = match kind {
            Builtin::Kick => (decay(0.25), decay(0.03)),
            Builtin::Snare => (decay(0.12), decay(0.02)),
            Builtin::Hat => (decay(0.035), 0.0),
        };
        Generator::Builtin {
            kind,
            amp_decay,
            pitch_decay,
        }
    }

    pub fn trigger(&self, voice: &mut Voice) {
        match self {
            Generator::Builtin { .. } => {
                voice.amp = 1.0;
                voice.pitch_env = 1.0;
                voice.phase = 0.0;
            }
            Generator::Sampler { .. } => {
                voice.amp = 1.0;
                voice.pos = 0.0;
            }
        }
    }

    /// The next sample of `voice`. Silent voices cost one comparison.
    pub fn next(&self, voice: &mut Voice, ctx: &mut Ctx) -> f32 {
        if voice.amp < 1.0e-4 {
            voice.amp = 0.0;
            return 0.0;
        }
        match self {
            Generator::Builtin {
                kind,
                amp_decay,
                pitch_decay,
            } => {
                let out = match kind {
                    Builtin::Kick => {
                        let freq = 50.0 + 110.0 * voice.pitch_env;
                        voice.sine(freq, ctx.sample_rate)
                    }
                    Builtin::Snare => {
                        let tone = voice.sine(180.0 + 40.0 * voice.pitch_env, ctx.sample_rate);
                        0.35 * tone + 0.65 * voice.highpass(ctx.noise.next(), 0.3)
                    }
                    Builtin::Hat => 0.45 * voice.highpass(ctx.noise.next(), 0.7),
                };
                let out = out * voice.amp;
                voice.amp *= amp_decay;
                voice.pitch_env *= pitch_decay;
                out
            }
            Generator::Sampler { sample, step } => {
                let i = voice.pos as usize;
                let frac = (voice.pos - voice.pos.floor()) as f32;
                let (Some(a), b) = (sample.frames.get(i), sample.frames.get(i + 1)) else {
                    voice.amp = 0.0;
                    return 0.0;
                };
                voice.pos += step;
                a + (b.copied().unwrap_or(0.0) - a) * frac
            }
        }
    }
}

/// Playing state of one channel.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Voice {
    amp: f32,
    pitch_env: f32,
    phase: f32,
    lowpass: f32,
    /// Read position in a sample, in sample frames.
    pos: f64,
}

impl Voice {
    fn sine(&mut self, freq: f32, sample_rate: f32) -> f32 {
        self.phase = (self.phase + freq / sample_rate).fract();
        (self.phase * std::f32::consts::TAU).sin()
    }

    /// Input minus a one-pole lowpass; `coeff` closer to 1 cuts more lows.
    fn highpass(&mut self, input: f32, coeff: f32) -> f32 {
        self.lowpass += (1.0 - coeff) * (input - self.lowpass);
        input - self.lowpass
    }
}

/// Shared per-sample context for generators.
pub struct Ctx {
    pub sample_rate: f32,
    pub noise: Noise,
}

/// xorshift32 white noise in [-1, 1).
pub struct Noise(u32);

impl Noise {
    pub fn new(seed: u32) -> Self {
        Self(seed.max(1))
    }

    pub fn next(&mut self) -> f32 {
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

    fn ctx() -> Ctx {
        Ctx {
            sample_rate: 48_000.0,
            noise: Noise::new(1),
        }
    }

    #[test]
    fn a_sampler_plays_its_sample_once_per_trigger() {
        let sample = Arc::new(Sample {
            frames: vec![0.5, 1.0, -1.0],
            rate: 48_000,
        });
        let generator = Generator::sampler(sample, 48_000.0);
        let mut voice = Voice::default();
        let mut ctx = ctx();
        assert_eq!(
            generator.next(&mut voice, &mut ctx),
            0.0,
            "silent until triggered"
        );
        generator.trigger(&mut voice);
        let out: Vec<f32> = (0..5)
            .map(|_| generator.next(&mut voice, &mut ctx))
            .collect();
        assert_eq!(out, [0.5, 1.0, -1.0, 0.0, 0.0]);
    }

    #[test]
    fn a_sampler_resamples() {
        let sample = Arc::new(Sample {
            frames: vec![0.0, 1.0],
            rate: 24_000,
        });
        let generator = Generator::sampler(sample, 48_000.0);
        let mut voice = Voice::default();
        let mut ctx = ctx();
        generator.trigger(&mut voice);
        let out: Vec<f32> = (0..5)
            .map(|_| generator.next(&mut voice, &mut ctx))
            .collect();
        assert_eq!(
            out,
            [0.0, 0.5, 1.0, 0.5, 0.0],
            "half-rate sample plays at half speed, easing out to silence"
        );
    }
}
