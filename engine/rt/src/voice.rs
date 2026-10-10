//! Sound sources. A voice's state is a few plain numbers, so it can be copied
//! from one graph to the next when the graph is swapped, and nothing here
//! allocates.

/// The synthesized drums the engine was born with.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Builtin {
    Kick,
    Snare,
    Hat,
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
}

impl Generator {
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
