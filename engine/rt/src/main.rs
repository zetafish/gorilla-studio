//! gorilla-engine: plays the song on the default output device.

mod control;
mod seq;

use std::error::Error;
use std::net::TcpListener;
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample, Stream, StreamConfig};
use rtrb::{Consumer, RingBuffer};

use control::Status;
use seq::{RtMsg, Sequencer};

/// Aborts on any allocation inside `assert_no_alloc` in debug builds, which is
/// where the audio callback runs.
#[cfg(debug_assertions)]
#[global_allocator]
static ALLOCATOR: assert_no_alloc::AllocDisabler = assert_no_alloc::AllocDisabler;

const BPM: f32 = 120.0;

/// Where the brain connects. Override with `GORILLA_ENGINE_ADDR`; keep it on
/// loopback, the protocol has no authentication.
const DEFAULT_ADDR: &str = "127.0.0.1:7878";

/// Patches waiting for the audio thread. Far more than one song edit needs.
const RT_QUEUE: usize = 1024;

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("gorilla-engine: {err}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), Box<dyn Error>> {
    let addr = std::env::var("GORILLA_ENGINE_ADDR").unwrap_or_else(|_| DEFAULT_ADDR.to_string());
    let listener = TcpListener::bind(&addr).map_err(|e| format!("cannot listen on {addr}: {e}"))?;
    let (tx, rx) = RingBuffer::new(RT_QUEUE);

    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .ok_or("no default output device")?;
    let supported = device.default_output_config()?;
    let format = supported.sample_format();
    let config: StreamConfig = supported.into();
    if config.channels == 0 {
        return Err("output device reports zero channels".into());
    }

    let stream_errors = Arc::new(AtomicU64::new(0));
    let stream = match format {
        SampleFormat::F32 => build::<f32>(&device, &config, rx, &stream_errors)?,
        SampleFormat::F64 => build::<f64>(&device, &config, rx, &stream_errors)?,
        SampleFormat::I16 => build::<i16>(&device, &config, rx, &stream_errors)?,
        SampleFormat::I32 => build::<i32>(&device, &config, rx, &stream_errors)?,
        SampleFormat::U16 => build::<u16>(&device, &config, rx, &stream_errors)?,
        other => return Err(format!("unsupported sample format {other}").into()),
    };
    stream.play()?;
    eprintln!(
        "gorilla-engine: playing at {} Hz, {} channels, {BPM} BPM",
        config.sample_rate, config.channels
    );

    let status = Arc::new(Status::default());
    let control_status = Arc::clone(&status);
    std::thread::Builder::new()
        .name("control".into())
        .spawn(move || control::serve(listener, tx, control_status))?;
    eprintln!("gorilla-engine: waiting for the brain on {addr}");

    // The stream stays alive as long as this thread holds it.
    let mut reported_errors = 0;
    let mut reported_epoch = 0;
    loop {
        std::thread::sleep(Duration::from_secs(1));
        let errors = stream_errors.load(Ordering::Relaxed);
        if errors != reported_errors {
            eprintln!(
                "gorilla-engine: {} stream errors",
                errors.saturating_sub(reported_errors)
            );
            reported_errors = errors;
        }
        let epoch = status.epoch.load(Ordering::Acquire);
        if epoch != reported_epoch {
            eprintln!("gorilla-engine: playing song epoch {epoch}");
            reported_epoch = epoch;
        }
    }
}

fn build<T>(
    device: &cpal::Device,
    config: &StreamConfig,
    mut rx: Consumer<RtMsg>,
    stream_errors: &Arc<AtomicU64>,
) -> Result<Stream, Box<dyn Error>>
where
    T: SizedSample + FromSample<f32>,
{
    let channels = usize::from(config.channels);
    let mut seq = Sequencer::new(config.sample_rate as f32, BPM);
    let errors = Arc::clone(stream_errors);

    let stream = device.build_output_stream(
        *config,
        move |data: &mut [T], _: &cpal::OutputCallbackInfo| {
            assert_no_alloc::assert_no_alloc(|| {
                // Patches land here, at the buffer boundary.
                while let Ok(msg) = rx.pop() {
                    seq.apply(msg);
                }
                render(&mut seq, data, channels);
            });
        },
        // May run on the audio thread, so only count; the main thread reports.
        move |_err| {
            errors.fetch_add(1, Ordering::Relaxed);
        },
        None,
    )?;
    Ok(stream)
}

fn render<T>(seq: &mut Sequencer, data: &mut [T], channels: usize)
where
    T: SizedSample + FromSample<f32>,
{
    for frame in data.chunks_mut(channels) {
        let value = T::from_sample(seq.next_sample());
        for sample in frame {
            *sample = value;
        }
    }
}
