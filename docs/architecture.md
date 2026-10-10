# Gorilla Studio architecture

Visual version: https://claude.ai/artifact/VUXMMT2V45q1M6JqFBf35x (private to the owner)

## Overview

Two processes joined by one narrow channel:

- **Brain (Clojure / JVM):** owns the song as an immutable value, plus editing, undo, the UI, live coding and agent access.
- **Engine (Rust, native):** owns the clock, the sound and the plugins. It plays its own copy of the song and never waits on the brain.

Song changes flow down as validated **patches**; meters, playhead and scopes flow up as **telemetry**. If the JVM pauses for 200 ms or you reload a namespace, the music keeps playing.

```
┌──────────────────────────── BRAIN (Clojure / JVM) ─────────────────────────────┐
│                                                                                │
│   nREPL ── MCP ── UI (HumbleUI desktop / re-frame web)                         │
│      │      │        │                                                         │
│      ▼      ▼        ▼                                                         │
│   ┌──────────────────────┐    ┌─────────────┐    ┌───────────────────────┐     │
│   │ Command registry     │───▶│ song atom   │───▶│ history (undo/redo)   │     │
│   │ id · malli schema ·fn│    │ (EDN value) │    │ vector of song values │     │
│   └──────────────────────┘    └──────┬──────┘    └───────────────────────┘     │
│                                      │ add-watch                               │
│   Live patterns (fns) ──lookahead──▶ │                                         │
│                                      ▼                                         │
│                          ┌───────────────────────┐                             │
│                          │ Differ / compiler     │  old song + new song        │
│                          │ → validated patches   │  → minimal op list          │
│                          └──────────┬────────────┘                             │
└─────────────────────────────────────┼──────────────────────────────────────────┘
                 patches (local socket, MessagePack)  ▲ telemetry (meters, playhead,
                                      │               │ scopes; shared mem @ 60 Hz)
┌─────────────────────────────────────▼───────────────┴──────────────────────────┐
│                               ENGINE (Rust)                                    │
│                                                                                │
│   Control thread: apply patch → build new graph off the RT thread              │
│        │                                                                       │
│        ▼  atomic pointer swap at buffer boundary                               │
│   ┌──────────────── RT audio thread (no alloc, no locks) ─────────────────┐    │
│   │ Sequencer (owns the clock, sample-accurate) → Graph: gens → FX → mix  │    │
│   └──────────────┬────────────────────────────────────────┬──────────────┘    │
│        old graphs │ dropped on a                 shared-mem│ audio buffers     │
│                   ▼ "reclaim" thread                       ▼                   │
│   Disk streamer · sample cache            ┌─────────────────────────────┐      │
│                                           │ Plugin sandboxes (1 process │      │
│                                           │ per plugin: VST3/CLAP/AU)   │      │
│                                           └─────────────────────────────┘      │
└────────────────────────────────────────────────────────────────────────────────┘
```

| Brain owns | Engine owns |
|---|---|
| The song value, editing, undo, UI, live patterns, every way an agent or person drives the app | The clock, sample-accurate playback, DSP, third-party plugins, disk streaming, offline export |

## 1. The song is one EDN value

```clojure
{:song/bpm 140
 :song/time-sig [4 4]
 :patterns {:drums {:steps {:kick  [1 0 0 0 1 0 0 0 1 0 0 0 1 0 0 0]
                            :hat   (euclid 5 16)          ; materialized on save
                            :snare [0 0 0 0 1 0 0 0 0 0 0 0 1 0 0 0]}}
            :lead  {:notes [{:t 0   :len 1/4 :pitch 67 :vel 0.8}
                            {:t 1/2 :len 1/4 :pitch 70 :vel 0.7}]}}
 :channels {:kick  {:gen [:sampler {:sample "kick-01.wav"}] :mixer 1}
            :lead  {:gen [:plugin {:format :clap :id "com.example.synth"}] :mixer 2}}
 :playlist [{:pattern :drums :track 0 :start 0 :len 16}
            {:pattern :lead  :track 1 :start 8 :len 8}]
 :mixer {1 {:vol 0.9 :fx [[:eq {}] [:comp {}]] :send {:reverb 0.2}}
         2 {:vol 0.7 :fx [[:reverb {:size 0.8}]]}}}
```

- **Saving:** the song file is this value written out, plus an `assets/` folder. Diffable and mergeable in git; readable by agents.
- **Undo:** `(swap! history conj @song)`. Structural sharing keeps every version cheap.
- **Validation:** one malli schema for the whole song. Every command's result is checked before it's committed.

## 2. Commands are data

```clojure
(defcommand :pattern/toggle-step
  {:params [:map [:pattern keyword?] [:row keyword?] [:step [:int {:min 0 :max 63}]]]
   :label "Toggle step"}
  (fn [song {:keys [pattern row step]}]
    (update-in song [:patterns pattern :steps row step] #(- 1 %))))

(dispatch {:cmd :pattern/toggle-step :pattern :drums :row :hat :step 3})
```

- UI, REPL, MCP and keyboard shortcuts all go through the same `dispatch`.
- A command is a pure function from song to song: easy to test, replay, and cover with generated property tests.

## 3. The differ turns song changes into patches

The engine never sees EDN. A watch on the song atom diffs old and new values and sends the smallest list of operations:

```clojure
{:epoch 42
 :ops [{:op :pattern/set-steps :pattern :drums :row :hat :steps [1 0 0 1 0 0 1 0 ...]}
       {:op :mixer/set-param :mixer 1 :param :vol :value 0.85}
       {:op :graph/insert-fx :mixer 2 :slot 0 :fx [:delay {:time 3/16}]}]}
```

- **Wire format:** each message is a 4-byte big-endian length and a MessagePack map, at most 64 KiB. Ops are maps tagged by `:op` (a serde internally tagged enum). Keywords travel as strings holding their full name, so `:pattern/set-steps` is `"pattern/set-steps"`.

- Validated twice: malli in the brain, serde-parsed Rust types in the engine. Malformed input is rejected; current state stays untouched.
- Each patch carries an epoch so the engine can report which song version it's playing.
- **`engine/proto` is the single source of truth.** Rust patch types generate the malli schemas, so the sides can't drift. A new patch type is a compile error until both sides handle it.
- **Schema generation (for now):** `gorilla_proto::malli_schema()` renders `brain/src/gorilla/proto_schema.cljc`, written next to the types it mirrors. A Rust test fails when the checked-in file is stale, and proto tests decode hand-built brain-shaped messages. Regenerate with `cargo run -p gorilla-proto --bin gen-malli > ../brain/src/gorilla/proto_schema.cljc` from `engine/`.

## 4. Engine threads and the real-time rules

At 128 samples and 48 kHz, each buffer must be ready in 2.67 ms. Missing one deadline produces an audible click.

| Thread | Job | Rules |
|---|---|---|
| Real-time audio | Sequencer and graph processing, every buffer | No allocation, locks, I/O or syscalls. `assert_no_alloc` in debug builds |
| Control | Receives patches, builds the next graph | Free to allocate. Hands over the finished graph with an atomic pointer swap |
| Reclaim | Drops old graphs and buffers | Keeps deallocation off the real-time thread |
| Disk streamer | Streams long audio files, fills the sample cache | Talks to the RT thread through lock-free ring buffers (`rtrb`) |
| Telemetry | Meters, playhead, scopes, spectra to the brain at ~60 Hz | Shared memory; the RT thread only writes |
| Plugin sandboxes | One OS process per third-party plugin | Audio through shared memory with semaphores. A crash takes out only that slot |

- **Graph swaps:** the audio thread always runs one immutable graph; a new one appears between buffers.
- **Parameter changes** (volume, cutoff) skip graph rebuilds: per-parameter lock-free queues, smoothed to avoid zipper noise.
- **Parallelism:** independent tracks render on a fixed, real-time-safe worker pool, ordered by dependencies.
- **Plugin hosting** is the only place that needs `unsafe`, isolated in `engine/host`.

## 5. Live coding

Clojure isn't real-time, so the brain never schedules notes. It computes ahead and sends data.

```clojure
(defpattern :hat
  (fn [bar]                                   ; pure fn: bar number → steps
    (if (zero? (mod bar 4))
      (euclid 7 16)
      (rotate (euclid 5 16) bar))))

(defpattern :lead
  (fn [bar]
    (->> (scale :c4 :minor-pentatonic)
         (markov-walk {:seed bar :len 8})
         (rhythm [1/4 1/8 1/8 1/2]))))
```

- **Lookahead:** a loop evaluates each live pattern ~2 bars ahead and writes concrete notes into the song.
- **Quantized edits:** redefining a pattern takes effect at the next bar or beat.
- **Safe files:** live patterns are saved as materialized output, with source kept as a string. Songs open without evaluating code.
- **Expressions** inside the song run in SCI (sandboxed, no I/O).

## 6. UI = f(song)

- Desktop: HumbleUI (Skia). Web: re-frame in Tauri or the browser. The UI reads the song atom and dispatches commands; it is never the source of truth.
- Piano roll, playlist and step sequencer are custom-drawn canvases.
- Waveforms, meters and playhead come from engine telemetry and are never stored in the song.

## 7. Failure modes

| Failure | Result |
|---|---|
| GC pause or namespace reload | Nothing audible; the engine plays from its own copy |
| Brain crashes | Music keeps playing. New brain reconnects, asks for the epoch, sends a full snapshot |
| Engine crashes | Brain restarts it and sends the full song; playback resumes from the last position |
| Plugin crashes | Only its sandbox dies; slot bypassed, user notified, plugin reloadable |
| Bad patch or invalid song | Rejected by malli, then serde; current state untouched |
| Missed buffer deadline | Logged with the slow part of the graph; shown in a per-track CPU meter |

## 8. Targets

| Target | Brain | Engine |
|---|---|---|
| Desktop | JVM or GraalVM native image | Native process, full plugin hosting |
| Web | ClojureScript sharing `.cljc` model, commands, differ, patterns | WASM in an AudioWorklet; no third-party plugins |
| Offline export | Sends a song snapshot | Same engine, faster than real time, bit-identical with playback |
| Headless / agent | No UI; nREPL or MCP | Same as desktop |

## 9. Repository layout

```
brain/                      Clojure (.cljc where shared)
  song/       model, malli schema, file format
  command/    registry + all commands
  diff/       song → patches compiler
  live/       defpattern, lookahead scheduler, generators (euclid, markov, scales)
  ui/         HumbleUI + re-frame views
  agent/      nREPL helpers, MCP server
engine/                     Rust workspace
  proto/      patch + telemetry types (serde; generates the malli schema)
  graph/      node graph, scheduler, parameter smoothing
  rt/         audio thread, sequencer, device I/O (cpal)
  dsp/        built-in synths + effects (fundsp)
  sampler/    disk streaming, sample cache (symphonia, rubato)
  host/       plugin sandbox protocol (the only crate with unsafe)
  render/     offline export
```

## Key crates and libraries

| Need | Choice |
|---|---|
| Audio device I/O | `cpal` |
| Lock-free ring buffers | `rtrb` |
| Allocation guard | `assert_no_alloc` |
| DSP building blocks | `fundsp`, `dasp` |
| Decoding | `symphonia` |
| Resampling | `rubato` |
| Plugins (build) | `nih-plug`; CLAP primary |
| Wire format | `serde` + MessagePack (`rmp-serde`) |
| Brain schemas | malli |
| Sandboxed expressions | SCI |
| Desktop UI | HumbleUI |
| Web UI | re-frame |

## Open questions

- Generating malli schemas from Rust types: the hand-rendered, golden-tested `malli_schema()` is enough for one patch type. Revisit (derive macro, or `schemars` JSON Schema → malli) once there are several.
- Plugin sandbox audio: synchronous round-trip each buffer (no extra latency, tighter deadline) or one buffer of added latency?
- Does the brain ship as a JVM app or a GraalVM native image on desktop?
