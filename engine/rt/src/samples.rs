//! Sample loading, on the control thread. Files are decoded once, mixed down
//! to mono and shared with the audio thread as `Arc<Sample>`; the audio thread
//! only reads them.

use std::collections::HashMap;
use std::fmt;
use std::fs::File;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use symphonia::core::codecs::audio::AudioDecoderOptions;
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, TrackType};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

/// Longest sample accepted: 10 minutes at 48 kHz. Longer audio belongs to the
/// disk streamer, which doesn't exist yet.
const MAX_FRAMES: usize = 10 * 60 * 48_000;

#[derive(PartialEq)]
pub struct Sample {
    /// Mono frames in [-1, 1].
    pub frames: Vec<f32>,
    /// The file's own sample rate; playback resamples on the fly.
    pub rate: u32,
}

impl fmt::Debug for Sample {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Sample({} frames @ {} Hz)", self.frames.len(), self.rate)
    }
}

/// Decoded samples by path. Nothing is evicted yet.
#[derive(Default)]
pub struct SampleCache(HashMap<PathBuf, Arc<Sample>>);

impl SampleCache {
    pub fn get(&mut self, path: &Path) -> Result<Arc<Sample>, String> {
        if let Some(sample) = self.0.get(path) {
            return Ok(Arc::clone(sample));
        }
        let sample = Arc::new(load(path).map_err(|e| format!("{}: {e}", path.display()))?);
        self.0.insert(path.to_path_buf(), Arc::clone(&sample));
        Ok(sample)
    }
}

pub fn load(path: &Path) -> Result<Sample, String> {
    if !path.is_absolute() {
        return Err("sample paths must be absolute".into());
    }
    let file = File::open(path).map_err(|e| e.to_string())?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());
    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
        hint.with_extension(ext);
    }
    let mut format = symphonia::default::get_probe()
        .probe(
            &hint,
            mss,
            FormatOptions::default(),
            MetadataOptions::default(),
        )
        .map_err(|e| e.to_string())?;
    let track = format
        .default_track(TrackType::Audio)
        .ok_or("no audio track")?;
    let track_id = track.id;
    let params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .ok_or("not an audio codec")?;
    let mut decoder = symphonia::default::get_codecs()
        .make_audio_decoder(params, &AudioDecoderOptions::default())
        .map_err(|e| e.to_string())?;

    let mut frames = Vec::new();
    let mut rate = 0;
    let mut interleaved: Vec<f32> = Vec::new();
    loop {
        let packet = match format.next_packet() {
            Ok(Some(packet)) => packet,
            Ok(None) => break,
            Err(SymphoniaError::ResetRequired) => break,
            Err(e) => return Err(e.to_string()),
        };
        if packet.track_id != track_id {
            continue;
        }
        let buf = match decoder.decode(&packet) {
            Ok(buf) => buf,
            Err(SymphoniaError::DecodeError(_)) => continue,
            Err(e) => return Err(e.to_string()),
        };
        rate = buf.spec().rate();
        let channels = buf.num_planes().max(1);
        interleaved.resize(buf.samples_interleaved(), 0.0);
        buf.copy_to_slice_interleaved(&mut interleaved);
        frames.extend(
            interleaved
                .chunks(channels)
                .map(|frame| frame.iter().sum::<f32>() / channels as f32),
        );
        if frames.len() > MAX_FRAMES {
            return Err(format!("longer than {MAX_FRAMES} frames"));
        }
    }
    if rate == 0 || frames.is_empty() {
        return Err("no audio decoded".into());
    }
    Ok(Sample { frames, rate })
}

#[cfg(test)]
pub mod tests {
    use super::*;

    /// Writes a 16-bit mono WAV of `frames` to a temp file.
    pub fn write_wav(name: &str, rate: u32, frames: &[i16]) -> PathBuf {
        let data_len = u32::try_from(frames.len() * 2).unwrap_or(0);
        let mut bytes = Vec::new();
        bytes.extend(b"RIFF");
        bytes.extend((36 + data_len).to_le_bytes());
        bytes.extend(b"WAVEfmt ");
        bytes.extend(16u32.to_le_bytes());
        bytes.extend(1u16.to_le_bytes()); // PCM
        bytes.extend(1u16.to_le_bytes()); // mono
        bytes.extend(rate.to_le_bytes());
        bytes.extend((rate * 2).to_le_bytes());
        bytes.extend(2u16.to_le_bytes());
        bytes.extend(16u16.to_le_bytes());
        bytes.extend(b"data");
        bytes.extend(data_len.to_le_bytes());
        for f in frames {
            bytes.extend(f.to_le_bytes());
        }
        let path =
            std::env::temp_dir().join(format!("gorilla-test-{}-{name}.wav", std::process::id()));
        assert!(std::fs::write(&path, bytes).is_ok());
        path
    }

    #[test]
    fn loads_a_wav() {
        let path = write_wav("load", 44_100, &[0, 16_384, -16_384, 32_767]);
        let sample = load(&path);
        assert!(sample.is_ok(), "{sample:?}");
        let Ok(sample) = sample else { return };
        assert_eq!(sample.rate, 44_100);
        assert_eq!(sample.frames.len(), 4);
        assert!((sample.frames.get(1).copied().unwrap_or(0.0) - 0.5).abs() < 0.001);
    }

    #[test]
    fn refuses_bad_paths_and_files() {
        assert!(load(Path::new("relative.wav")).is_err());
        assert!(load(Path::new("/definitely/not/here.wav")).is_err());
        let junk = std::env::temp_dir().join(format!("gorilla-junk-{}.wav", std::process::id()));
        assert!(std::fs::write(&junk, b"not audio").is_ok());
        assert!(load(&junk).is_err());
    }

    #[test]
    fn loads_macos_system_sounds_when_present() {
        let pop = Path::new("/System/Library/Sounds/Pop.aiff");
        if pop.exists() {
            let sample = load(pop);
            assert!(sample.is_ok_and(|s| !s.frames.is_empty() && s.rate > 0));
        }
    }

    #[test]
    fn the_cache_shares_decoded_samples() {
        let path = write_wav("cache", 48_000, &[1, 2, 3]);
        let mut cache = SampleCache::default();
        let a = cache.get(&path);
        let b = cache.get(&path);
        assert!(matches!((a, b), (Ok(a), Ok(b)) if Arc::ptr_eq(&a, &b)));
    }
}
