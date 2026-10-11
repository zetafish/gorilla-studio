//! Sound sources. A voice's state is a few plain numbers, so it can be copied
//! from one graph to the next when the graph is swapped, and nothing here
//! allocates.

use std::f32::consts::TAU;
use std::sync::Arc;

use crate::samples::Sample;

/// The engine's own instruments.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Builtin {
    Kick,
    Snare,
    Hat,
    /// A saw bass with a filter that closes after each note.
    Bass,
    /// Two detuned saws, softer attack and release.
    Lead,
}

impl Builtin {
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "kick" => Some(Builtin::Kick),
            "snare" => Some(Builtin::Snare),
            "hat" => Some(Builtin::Hat),
            "bass" => Some(Builtin::Bass),
            "lead" => Some(Builtin::Lead),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Drum {
    Kick,
    Snare,
    Hat,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Synth {
    Bass,
    Lead,
}

/// What a channel plays. Built on the control thread; read-only on the audio
/// thread.
#[derive(Debug, Clone, PartialEq)]
pub enum Generator {
    /// One-shot drums: pitch is ignored, velocity sets the level.
    Drum {
        kind: Drum,
        amp_decay: f32,
        pitch_decay: f32,
    },
    /// Held notes with an envelope: attack, decay to sustain, release when
    /// the note's length runs out.
    Synth {
        kind: Synth,
        /// Envelope rise per sample.
        attack: f32,
        /// Per-sample factors towards sustain, towards silence, and for the
        /// filter sweep.
        decay: f32,
        sustain: f32,
        release: f32,
        filter_decay: f32,
    },
    /// Plays a sample once per trigger, resampled by linear interpolation.
    /// Pitch 60 plays it as recorded; each semitone shifts the rate.
    Sampler {
        sample: Arc<Sample>,
        /// Sample frames per output frame at pitch 60.
        step: f64,
    },
}

/// The pitch at which a sample plays as recorded.
pub const ROOT_PITCH: u8 = 60;

const OFF: u8 = 0;
const ATTACK: u8 = 1;
const SUSTAIN: u8 = 2;
const RELEASE: u8 = 3;

impl Generator {
    pub fn builtin(kind: Builtin, sample_rate: f32) -> Self {
        let sr = sample_rate.max(1.0);
        let decay = |seconds: f32| (-1.0 / (seconds * sr)).exp();
        let drum = |kind, amp: f32, pitch: f32| Generator::Drum {
            kind,
            amp_decay: decay(amp),
            pitch_decay: if pitch > 0.0 { decay(pitch) } else { 0.0 },
        };
        let synth =
            |kind, attack: f32, dec: f32, sustain, release: f32, filter: f32| Generator::Synth {
                kind,
                attack: 1.0 / (attack * sr).max(1.0),
                decay: decay(dec),
                sustain,
                release: decay(release),
                filter_decay: decay(filter),
            };
        match kind {
            Builtin::Kick => drum(Drum::Kick, 0.25, 0.03),
            Builtin::Snare => drum(Drum::Snare, 0.12, 0.02),
            Builtin::Hat => drum(Drum::Hat, 0.035, 0.0),
            Builtin::Bass => synth(Synth::Bass, 0.003, 0.2, 0.6, 0.08, 0.12),
            Builtin::Lead => synth(Synth::Lead, 0.01, 0.3, 0.8, 0.15, 0.4),
        }
    }

    pub fn sampler(sample: Arc<Sample>, sample_rate: f32) -> Self {
        let step = f64::from(sample.rate) / f64::from(sample_rate.max(1.0));
        Generator::Sampler { sample, step }
    }

    /// Starts a note. `vel` is 0..1.
    pub fn trigger(&self, voice: &mut Voice, pitch: u8, vel: f32) {
        let vel = vel.clamp(0.0, 1.0);
        match self {
            Generator::Drum { .. } => {
                voice.amp = vel;
                voice.pitch_env = 1.0;
                voice.phase = 0.0;
            }
            Generator::Synth { .. } => {
                voice.freq = midi_to_hz(pitch);
                voice.vel = vel;
                voice.stage = ATTACK;
                voice.filter_env = 1.0;
                // Keep the level and phase from a note still sounding, so
                // back-to-back notes don't click.
            }
            Generator::Sampler { .. } => {
                voice.amp = vel;
                voice.pos = 0.0;
                voice.rate = 2f64.powf((f64::from(pitch) - f64::from(ROOT_PITCH)) / 12.0);
            }
        }
    }

    /// Ends a held note. One-shots ignore it.
    pub fn release(&self, voice: &mut Voice) {
        if let Generator::Synth { .. } = self
            && voice.stage != OFF
        {
            voice.stage = RELEASE;
        }
    }

    /// The next sample of `voice`. Silent voices cost a comparison or two.
    pub fn next(&self, voice: &mut Voice, ctx: &mut Ctx) -> f32 {
        match self {
            Generator::Drum {
                kind,
                amp_decay,
                pitch_decay,
            } => {
                if voice.amp < 1.0e-4 {
                    voice.amp = 0.0;
                    return 0.0;
                }
                let out = match kind {
                    Drum::Kick => {
                        let freq = 50.0 + 110.0 * voice.pitch_env;
                        voice.sine(freq, ctx.sample_rate)
                    }
                    Drum::Snare => {
                        let tone = voice.sine(180.0 + 40.0 * voice.pitch_env, ctx.sample_rate);
                        0.35 * tone + 0.65 * voice.highpass(ctx.noise.next(), 0.3)
                    }
                    Drum::Hat => 0.45 * voice.highpass(ctx.noise.next(), 0.7),
                };
                let out = out * voice.amp;
                voice.amp *= amp_decay;
                voice.pitch_env *= pitch_decay;
                out
            }
            Generator::Synth {
                kind,
                attack,
                decay,
                sustain,
                release,
                filter_decay,
            } => {
                match voice.stage {
                    ATTACK => {
                        voice.env += attack;
                        if voice.env >= 1.0 {
                            voice.env = 1.0;
                            voice.stage = SUSTAIN;
                        }
                    }
                    SUSTAIN => voice.env = sustain + (voice.env - sustain) * decay,
                    RELEASE => {
                        voice.env *= release;
                        if voice.env < 1.0e-4 {
                            voice.env = 0.0;
                            voice.stage = OFF;
                        }
                    }
                    _ => return 0.0,
                }
                let sr = ctx.sample_rate;
                let (osc, base_cutoff, sweep) = match kind {
                    Synth::Bass => (voice.saw(voice.freq, sr), 180.0, 2_400.0),
                    Synth::Lead => {
                        let a = voice.saw(voice.freq * 0.996, sr);
                        let b = voice.saw2(voice.freq * 1.004, sr);
                        (0.5 * (a + b), 1_400.0, 2_000.0)
                    }
                };
                let cutoff = base_cutoff + sweep * voice.filter_env;
                voice.filter_env *= filter_decay;
                let coeff = (TAU * cutoff / sr).min(1.0);
                voice.lowpass += coeff * (osc - voice.lowpass);
                0.5 * voice.lowpass * voice.env * voice.vel
            }
            Generator::Sampler { sample, step } => {
                if voice.amp < 1.0e-4 {
                    return 0.0;
                }
                let i = voice.pos as usize;
                let frac = (voice.pos - voice.pos.floor()) as f32;
                let (Some(a), b) = (sample.frames.get(i), sample.frames.get(i + 1)) else {
                    voice.amp = 0.0;
                    return 0.0;
                };
                voice.pos += step * voice.rate;
                (a + (b.copied().unwrap_or(0.0) - a) * frac) * voice.amp
            }
        }
    }
}

pub fn midi_to_hz(pitch: u8) -> f32 {
    440.0 * 2f32.powf((f32::from(pitch) - 69.0) / 12.0)
}

/// Playing state of one channel.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Voice {
    amp: f32,
    pitch_env: f32,
    phase: f32,
    phase2: f32,
    lowpass: f32,
    /// Read position in a sample, in sample frames, and its playback rate.
    pos: f64,
    rate: f64,
    /// Synth note state.
    freq: f32,
    vel: f32,
    env: f32,
    filter_env: f32,
    stage: u8,
}

impl Voice {
    fn sine(&mut self, freq: f32, sample_rate: f32) -> f32 {
        self.phase = (self.phase + freq / sample_rate).fract();
        (self.phase * TAU).sin()
    }

    /// Band-limited saw (PolyBLEP), so high notes don't alias harshly.
    fn saw(&mut self, freq: f32, sample_rate: f32) -> f32 {
        let dt = (freq / sample_rate).clamp(0.0, 0.5);
        self.phase = (self.phase + dt).fract();
        2.0 * self.phase - 1.0 - poly_blep(self.phase, dt)
    }

    fn saw2(&mut self, freq: f32, sample_rate: f32) -> f32 {
        let dt = (freq / sample_rate).clamp(0.0, 0.5);
        self.phase2 = (self.phase2 + dt).fract();
        2.0 * self.phase2 - 1.0 - poly_blep(self.phase2, dt)
    }

    /// Input minus a one-pole lowpass; `coeff` closer to 1 cuts more lows.
    fn highpass(&mut self, input: f32, coeff: f32) -> f32 {
        self.lowpass += (1.0 - coeff) * (input - self.lowpass);
        input - self.lowpass
    }
}

fn poly_blep(t: f32, dt: f32) -> f32 {
    if dt <= 0.0 {
        0.0
    } else if t < dt {
        let t = t / dt;
        t + t - t * t - 1.0
    } else if t > 1.0 - dt {
        let t = (t - 1.0) / dt;
        t * t + t + t + 1.0
    } else {
        0.0
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

    const SR: f32 = 48_000.0;

    fn ctx() -> Ctx {
        Ctx {
            sample_rate: SR,
            noise: Noise::new(1),
        }
    }

    fn run(generator: &Generator, voice: &mut Voice, n: usize) -> Vec<f32> {
        let mut ctx = ctx();
        (0..n).map(|_| generator.next(voice, &mut ctx)).collect()
    }

    fn peak(xs: &[f32]) -> f32 {
        xs.iter().fold(0.0_f32, |m, x| m.max(x.abs()))
    }

    #[test]
    fn a_sampler_plays_its_sample_once_per_trigger() {
        let sample = Arc::new(Sample {
            frames: vec![0.5, 1.0, -1.0],
            rate: 48_000,
        });
        let generator = Generator::sampler(sample, SR);
        let mut voice = Voice::default();
        assert_eq!(
            run(&generator, &mut voice, 1),
            [0.0],
            "silent until triggered"
        );
        generator.trigger(&mut voice, ROOT_PITCH, 1.0);
        assert_eq!(run(&generator, &mut voice, 5), [0.5, 1.0, -1.0, 0.0, 0.0]);
    }

    #[test]
    fn a_sampler_resamples_and_follows_pitch_and_velocity() {
        let sample = Arc::new(Sample {
            frames: vec![0.0, 1.0],
            rate: 24_000,
        });
        let generator = Generator::sampler(sample, SR);
        let mut voice = Voice::default();
        generator.trigger(&mut voice, ROOT_PITCH, 1.0);
        assert_eq!(
            run(&generator, &mut voice, 5),
            [0.0, 0.5, 1.0, 0.5, 0.0],
            "half-rate sample plays at half speed, easing out to silence"
        );
        generator.trigger(&mut voice, ROOT_PITCH + 12, 0.5);
        assert_eq!(
            run(&generator, &mut voice, 3),
            [0.0, 0.5, 0.0],
            "an octave up plays twice as fast, at half level"
        );
    }

    #[test]
    fn drums_follow_velocity() {
        let kick = Generator::builtin(Builtin::Kick, SR);
        let mut loud = Voice::default();
        let mut soft = Voice::default();
        kick.trigger(&mut loud, 60, 1.0);
        kick.trigger(&mut soft, 60, 0.25);
        let (l, s) = (run(&kick, &mut loud, 2_000), run(&kick, &mut soft, 2_000));
        assert!((peak(&s) / peak(&l) - 0.25).abs() < 0.01);
    }

    #[test]
    fn synths_hold_until_released_then_fade() {
        for kind in [Builtin::Bass, Builtin::Lead] {
            let synth = Generator::builtin(kind, SR);
            let mut voice = Voice::default();
            synth.trigger(&mut voice, 45, 1.0);
            let held = run(&synth, &mut voice, 24_000);
            assert!(held.iter().all(|x| x.is_finite() && x.abs() <= 1.0));
            assert!(peak(held.split_at(20_000).1) > 0.05, "{kind:?} sustains");
            synth.release(&mut voice);
            let tail = run(&synth, &mut voice, 96_000);
            assert_eq!(peak(tail.split_at(80_000).1), 0.0, "{kind:?} fades out");
        }
    }

    #[test]
    fn pitch_maps_to_frequency() {
        assert!((midi_to_hz(69) - 440.0).abs() < 1e-3);
        assert!((midi_to_hz(57) - 220.0).abs() < 1e-3);
    }
}
