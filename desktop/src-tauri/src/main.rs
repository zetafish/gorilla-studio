//! Gorilla Studio's Tauri shell. All it does is show the web UI (web/public)
//! in a native window; the UI talks to the brain over its WebSocket.

// No console window on Windows release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::process::ExitCode;

fn main() -> ExitCode {
    match tauri::Builder::default().run(tauri::generate_context!()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("gorilla-desktop: {err}");
            ExitCode::FAILURE
        }
    }
}
