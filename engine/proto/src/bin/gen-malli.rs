//! Prints the brain's malli schemas for the proto types, or with `examples`,
//! one encoded envelope per patch type for the brain's cross-check test.

use std::io::Write;
use std::process::ExitCode;

fn main() -> ExitCode {
    let result = match std::env::args().nth(1).as_deref() {
        None => std::io::stdout().write_all(gorilla_proto::malli_schema().as_bytes()),
        Some("examples") => gorilla_proto::examples_frames()
            .map_err(std::io::Error::other)
            .and_then(|bytes| std::io::stdout().write_all(&bytes)),
        Some(other) => Err(std::io::Error::other(format!("unknown argument {other:?}"))),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("gen-malli: {err}");
            ExitCode::FAILURE
        }
    }
}
