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
            :lead  {:notes {:lead [{:t 0 :len 4 :pitch 67 :vel 0.8}     ; times in steps
                                   {:t 8 :len 4 :pitch 70 :vel 0.7}]}}}
 :channels {:kick  {:gen [:sampler {:sample "assets/kick-01.wav"}] :mixer 1}
            :lead  {:gen [:builtin {:voice :lead}] :mixer 2}}  ; later [:plugin {...}]
 :playlist [{:pattern :drums :track 0 :start 0 :len 16}         ; in bars
            {:pattern :lead  :track 1 :start 8 :len 8}]
 :song/loop true
 :mixer {1 {:vol 0.9 :fx [[:eq {}] [:comp {}]] :send {:reverb 0.2}}
         2 {:vol 0.7 :fx [[:reverb {:size 0.8}]]}}}
```

- **Rows** are named after the channel they play. A row holds either steps (16 on/off) or notes: `{:t step :len steps :pitch midi :vel 0..1}`, one note starting per step, `:len` up to 64 steps. Each channel plays one note at a time; a note holds for `:len` steps, then releases.
- **Playlist:** clips place patterns on the timeline in bars. With no clips every pattern loops; with clips a pattern plays only in the bars its clips cover, and playback stops after the last clip unless `:song/loop` is true. `:track` is a display lane only.
- **Saving:** a song is a folder: `song.edn` holds `{:format 1 :song <this value> :live {...}}` and `assets/` holds a copy of every sample, referenced by relative path. The EDN is written in a fixed layout (sorted keys, one row per line) so it diffs and merges cleanly in git; saving twice gives identical bytes. Opening reads it with `clojure.edn` (no eval, no reader tags) and validates it before anything changes. `gorilla.file` has `save!` and `open!`.
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
- **Transport:** TCP on loopback, `127.0.0.1:7878` by default (`GORILLA_ENGINE_ADDR` overrides it). Chosen over Unix sockets because it works the same on macOS, Linux, Windows and the JVM. One brain connects at a time; the protocol has no authentication, so it must stay on loopback.
- **Song shape:** `:channel/set` (a builtin voice or a sampler with an absolute path), `:channel/remove` (also removes the rows that play it), `:pattern/set-steps` (creates the pattern and row on first use), `:pattern/remove-row`, `:pattern/remove`, `:song/set-bpm` and `:song/reset`. A row plays the channel with the same id, as in a channel rack. A full snapshot starts with `:song/reset`, so the engine ends up with exactly the brain's song; channels that survive the reset keep ringing.
- **Notes and arrangement:** `:pattern/set-notes` and `:pattern/schedule-notes` carry notes; the engine turns step rows and note rows into the same per-step hits (pitch, velocity, length). `:song/set-playlist` sends the whole arrangement (clips and looping); the engine decides at each bar line which patterns play, and stops at the end of a non-looping arrangement.
- **Cross-check:** `gorilla_proto::examples()` encodes one envelope per patch type into `brain/test/resources/proto-examples.bin` (golden-tested on the Rust side), and a brain test validates each against the generated malli schema.
- **Transport** (`{:op :transport/set :playing false}`) is engine state, not song data: it isn't saved, isn't undone and doesn't advance the epoch, so it's sent directly rather than through the differ. Stopping lets ringing voices decay; playing restarts at step 0.
- **Telemetry** comes back on the same socket, framed the same way: `{:op :engine/hello :engine-id 123 :epoch 41}` once per connection, then `{:op :engine/playhead :epoch 41 :playing true :bar 12 :step 3}` about 60 times a second. The engine id is random per engine process, so a reconnecting brain can tell whether the engine still holds the song it sent. When the engine refuses an envelope it sends `{:op :engine/rejected :epoch 41 :reason "..." :ops ["pattern/set-steps"]}`; the op names let the brain tell a rejected song change (resync) from a rejected live op (log it).
- **Rejection:** an envelope is applied whole or not at all. A bad payload is logged and skipped while the connection stays open; a bad length header drops the connection, since the stream can't be resynced.

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
| Telemetry | Meters, playhead, scopes, spectra to the brain at ~60 Hz | The RT thread only writes atomics (`Playhead`); a telemetry thread per connection reads them and writes frames back on the brain's socket. Shared memory once scopes and spectra need the bandwidth |
| Plugin sandboxes | One OS process per third-party plugin | Audio through shared memory with semaphores. A crash takes out only that slot |

- **Graph swaps:** the audio thread always runs one graph; a new one appears between buffers. The control thread keeps a model of the song (`engine/rt/src/model.rs`), builds a new graph from it when its shape changes (a new channel, row or pattern), and sends it down the same `rtrb` queue as other messages, so ordering is preserved. The graph carries maps from its channels and rows to the old graph's; on swap the audio thread copies voice state, steps and queued bars across, so ringing sounds don't cut off. The old graph goes back through a second `rtrb` queue to the reclaim thread.
- **Parameter changes** (volume, cutoff) skip graph rebuilds: per-parameter lock-free queues, smoothed to avoid zipper noise.
- **Pattern data** (step toggles, notes) also skips graph rebuilds. The control thread turns patches into fixed-size `Copy` messages on an `rtrb` queue, and the audio thread drains it at the start of each buffer. Live patterns use `:pattern/schedule-bar`: each row has a fixed queue of upcoming bars (`QUEUED_BARS`), and a queued bar replaces the row exactly at its bar line. The row keeps it afterwards, so without a brain the last bar loops.
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

- **Lookahead:** a scheduler (`gorilla.live`) watches the engine's playhead and evaluates each live pattern for the next 2 bars, sending the concrete steps as `:pattern/schedule-bar` patches. The engine switches rows exactly on the bar line; if the brain goes away, the last bar keeps looping.
- **Live output is not song data.** Earlier drafts wrote each generated bar into the song value. That would push a new song onto the undo history every bar and make the differ resend rows constantly, so generated bars go straight to the engine instead, like transport. The song keeps its own static rows; removing a live pattern sends the song's row back.
- **Quantized edits:** redefining a pattern re-sends the bars already queued, so the change lands on the next bar line.
- **Steps or notes:** a pattern function returns anything `steps` accepts, or a seq of notes. `gorilla.pattern` has step generators (`euclid`, `chance`, ...) and melodic ones (`note`, `scale`, `degree`, `chord`, `markov-walk`, `rhythm`, `transpose`).
- **Time budget:** each pattern renders a bar on a worker thread with 100 ms to finish. One that runs over is stopped (and logged) until it's redefined; the others keep going. The JVM can't kill a thread, so code that never yields may keep a core busy until it finishes.
- **Undo:** replacing the whole song (`new-song!`, `open!`) records the live patterns and the samples folder with the history entry, so undo brings them back with the song.
- **Starting playback** sends the transport op and the first bars in one envelope, so live rows are right from bar 0.
- **Safe files:** each live pattern is saved as its source string plus 32 materialized bars and `:loop-from`, the bar where they start repeating (found when saving). Opening evaluates nothing: live rows come back frozen, play their saved bars once, then loop from `:loop-from`. `(gorilla.live/activate!)` runs the saved source again in SCI, with pure `clojure.core` plus the `gorilla.pattern` generators, no I/O, no Java interop, no threads; a pattern whose source fails stays frozen.
- **Expressions** inside the song run in SCI (sandboxed, no I/O). So far that means live pattern source; anything else that runs from a file will use the same sandbox.

## 6. UI = f(song)

- Desktop: HumbleUI (Skia). Web: re-frame in Tauri or the browser. The UI reads the song atom and dispatches commands; it is never the source of truth.
- Piano roll, playlist and step sequencer are custom-drawn canvases.
- **Desktop UI as built** (`gorilla.ui`): `ui.model` turns the song into view data and turns a click position into a command (pure `.cljc`, unit-tested). `ui.app` draws that with HumbleUI. HumbleUI signals mirror the song atom and live patterns, so components re-render when they change; the playhead changes 60 times a second, so canvases read it while painting and a watch only requests a frame. UI-only state (selected pattern, open picker, status line) lives in one signal and never in the song or undo history.
- **Testing the UI:** `gorilla.ui.snapshot` mounts components without a window, draws them into a Skia raster surface (and PNG), and delivers mouse events to the tree, so clicks are tested end to end without a display.
- Waveforms, meters and playhead come from engine telemetry and are never stored in the song.

## 7. Failure modes

| Failure | Result |
|---|---|
| GC pause or namespace reload | Nothing audible; the engine plays from its own copy |
| Brain loses the link, engine keeps running | On reconnect the hello's engine id and epoch match what the brain last sent, so it sends only the changes made meanwhile |
| Brain crashes | Music keeps playing. The new brain reads the engine's hello (engine id, epoch) and sends a full snapshot; epochs continue from the engine's |
| Engine crashes | The brain's telemetry reader notices, `auto-connect!` restarts the engine (`cargo run` for now) and sends the full song and the transport state. Playback restarts from the top |
| Plugin crashes | Only its sandbox dies; slot bypassed, user notified, plugin reloadable |
| Bad patch or invalid song | Rejected by malli, then serde and the engine's own checks (unknown rows, unreadable samples, tempo range); current state untouched. The engine reports `:engine/rejected`; for song changes the brain resends the whole song once, and if that is refused too it waits for the next edit |
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
