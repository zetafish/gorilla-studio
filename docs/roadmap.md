# Gorilla Studio roadmap

Build in thin vertical slices: each milestone runs end to end, from the REPL to sound, before the next one starts. The design these milestones build towards is in [`architecture.md`](architecture.md).

Each milestone has **done when** criteria: the hand tests that close it. The automated tests must pass too, but they don't replace listening.

When a milestone finishes or a gap is closed, update this file in the same change.

## Status

| # | Milestone | Status |
|---|---|---|
| 1 | The vertical slice | Done |
| 2 | The engine talks back | Done |
| 3 | Live patterns | Done |
| 4 | The song drives the engine | Done |
| 5 | Save and load | Done |
| 6 | Melody and arrangement | Done |
| 7 | Desktop UI | Done, pending your hands-on test |
| 8 | Mixer and effects | **Next** |
| 9 | Agent access (MCP) | Planned |
| 10 | Offline export | Planned |
| 11 | Plugin hosting | Planned |
| 12 | Web target | Planned |

The order after milestone 6 is flexible; the UI moved ahead of the mixer so the app could be used without the REPL. Agent access (9) is cheap because every action is already a command, so it can move earlier whenever it's useful.

## Done

### 1. The vertical slice

Prove the two-process design end to end.

- The Rust engine plays a hard-coded 16-step drum pattern through `cpal`, with synthesized kick, snare and hat.
- `:pattern/set-steps` patches arrive over loopback TCP as length-prefixed MessagePack and are applied at a buffer boundary.
- The Clojure brain holds the song in an atom. Commands are pure and validated with malli, undo keeps a history of song values, and the differ sends only changed rows.
- Transport: `:transport/set` play and stop. Stopped voices ring out; play restarts at step 0.

**Done when:** toggling hi-hat steps from the REPL gives no audible glitch, and killing the brain leaves the beat playing.

### 2. The engine talks back

- The engine sends a hello on connect (a random engine id and its epoch), then the playhead at about 60 Hz on the same socket.
- On reconnect the brain sends only what the engine is missing: the changes made meanwhile to the same engine, or the whole song to a new one. Epochs continue from the engine's.
- `connect!` keeps the link up and restarts a crashed engine with `cargo run`.
- `(reload!)` reloads changed namespaces in dependency order and keeps the song.

**Done when:** `(playhead)` advances; `kill -9` on the engine brings a new engine back with the current song; disconnecting, editing and reconnecting sends only the edit.

### 3. Live patterns

- `(defpattern :hat (fn [bar] ...))`: rows as pure functions of the bar number.
- The scheduler evaluates live rows two bars ahead and sends `:pattern/schedule-bar`. The engine queues up to four bars per row and switches exactly on the bar line; the last bar loops if the brain goes away.
- Generators: `steps` (including `"x..x"` strings), `euclid`, `rotate`, `invert`, `overlay`, `mask`, `chance`, `every`.
- Generated bars go to the engine, not into the song, so they never touch undo history (see [architecture §5](architecture.md#5-live-coding)).

**Done when:** a pattern that varies every bar plays in time; redefining it lands on the next bar line; `play!` starts with live rows in place.

### 4. The song drives the engine

- **Channels:** the song has `:channels`, each with a generator: `[:builtin {:voice :kick}]` or `[:sampler {:sample "/path/clap.wav"}]`. A row plays the channel with the same id, as in a channel rack.
- **Any patterns and rows:** the engine keeps a model of the song on the control thread and builds a graph of channels and patterns from it. Every pattern loops at the same time until the playlist exists (milestone 6).
- **Graph swaps:** a change of shape builds a new graph and sends it down the audio queue; voices, steps and queued bars carry over by id, and a reclaim thread drops the old graph.
- **Samples:** decoded with `symphonia` (WAV, AIFF, FLAC) on the control thread, mixed to mono and played with linear-interpolation resampling. Relative paths resolve against `gorilla.engine/samples-root`.
- **Tempo:** `:song/bpm` reaches the engine; a change keeps the position within the step.
- **Snapshots** start with `:song/reset`, so a new engine ends up with exactly the brain's song, including transport state.
- **Rejections:** the engine reports `:engine/rejected` with the op names. A rejected song change makes the brain resend the whole song once; a rejected live op is only logged.
- **Cross-check:** one encoded example per patch type, golden-tested in Rust, validated against the malli schema in a brain test.

**Done when:** a song with two patterns and sample-based rows plays at song tempo; adding a channel while playing makes no click; changing BPM mid-play keeps the groove; a rejected patch shows up in the brain.

### 5. Save and load

- A song is a folder: `song.edn` (the song value, plus live patterns) and `assets/` with a copy of every sample; paths in the file are relative. `(save! "dir")`, `(open! "dir")`.
- `song.edn` is written in a fixed layout so it diffs cleanly; saving twice gives identical bytes.
- Live patterns are saved as source text plus 16 materialized bars. Opening evaluates nothing: they come back frozen, looping their saved bars.
- `(activate!)` runs saved source again in an SCI sandbox: pure `clojure.core` plus the pattern generators, no I/O, no interop, no threads.
- `:song/replace` is an undoable, validated command; `(new-song!)` starts empty.

**Done when:** save, quit both processes, open again, and it sounds the same; a file with live-pattern source opens without running it; the file diffs cleanly in git.

### 6. Melody and arrangement

- **Notes:** a row holds steps or notes (`{:t :len :pitch :vel}`, times in sixteenths). The engine turns both into per-step hits; a channel holds a note for its length, then releases it.
- **Synths:** built-in `:bass` and `:lead` (band-limited saws, filter and envelope). Samplers follow pitch (60 plays as recorded) and velocity.
- **Melodic generators:** `note`, `scale`, `degree`, `chord`, `markov-walk`, `rhythm`, `transpose`; live patterns may return notes.
- **Playlist:** clips of patterns over bars. The engine picks the patterns for each bar, and stops after the last clip or loops (`:song/loop`). No clips: every pattern loops, as before.
- **Closed gaps:** live patterns run on worker threads with a 100 ms budget per bar; undo after `new-song!` or `open!` restores live patterns and the samples folder; saving keeps 32 bars and where they start repeating, so a frozen intro plays once.
- **Fixed:** opening a second song whose samples had the same relative paths kept the first song's samples; the brain now diffs resolved paths.
- `songs.night`: an arranged A-minor song with a walking bass and a tune.

**Done when:** a live melodic pattern plays in key over a drum loop; an arrangement of several patterns plays start to finish.

### 7. Desktop UI

- **HumbleUI window:** top bar (play/stop, BPM, position, engine status, undo/redo, save/open), pattern list, channel rack for the selected pattern, and the playlist. `clojure -M:ui` starts it with the engine and an nREPL server; `(gorilla.ui.app/show!)` opens it from a REPL.
- **Rack:** every channel is a row of 16 steps; click toggles a step (creating the row if needed), note rows show and toggle notes, live rows are marked and show what they play this bar, × removes a channel. "+ Channel" offers the built-in voices and samples (macOS sounds and `brain/samples/`).
- **Playlist:** one lane per pattern; click a bar to add a clip (up to 4 bars, stopping at the next clip), click a clip to remove it; loop toggle; playhead line; clips playing now are brighter.
- **Shortcuts:** space play/stop, ⌘Z undo, ⇧⌘Z redo, ⌘S save. Save writes to the current folder or `brain/songs/untitled-N`; Open lists folders in `brain/songs/`.
- Everything goes through `dispatch!`, so every action can be undone. What to show and what a click means live in `gorilla.ui.model` (pure, tested); the UI is also tested off-screen by drawing into a Skia surface and sending it mouse events.

**Done when:** a beat can be built and arranged without the REPL, and every UI action can be undone.

## Next

### 8. Mixer and effects

- Mixer channels with volume, pan, sends and an effect chain (`fundsp`), and a mixer view in the UI.
- Parameter changes through per-parameter lock-free queues with smoothing, without graph rebuilds.
- Meters in telemetry, shown in the UI. Move telemetry to shared memory if meters and scopes need the bandwidth.

**Done when:** sweeping a filter cutoff from the REPL is smooth (no zipper noise); adding an effect while playing makes no click.

## Planned

### 9. Agent access (MCP)

- An MCP server that exposes the command registry: each command's id, label and params schema becomes a tool.
- Read access to the song value and the playhead.

**Done when:** an agent can build a beat through MCP, and every change it makes goes through `dispatch` and can be undone.

### 10. Offline export

- The `render` crate: the same engine, faster than real time, writing WAV.

**Done when:** an export is bit-identical to what plays live from the same song.

### 11. Plugin hosting

- CLAP first. One sandbox process per plugin, with audio through shared memory. `unsafe` only in `engine/host`.
- Decide the open question: a synchronous round-trip each buffer, or one buffer of added latency.

**Done when:** a CLAP synth plays a pattern; killing its sandbox bypasses that slot, the rest keeps playing, and the plugin can be reloaded.

### 12. Web target

- ClojureScript shares the `.cljc` song model, commands, differ and patterns.
- The engine compiled to WASM in an AudioWorklet. No third-party plugins.

**Done when:** the same song plays in the browser and on desktop.

## Known gaps

Things that work but aren't finished, with the milestone that should close each one.

| Gap | Why it matters | Close in |
|---|---|---|
| The malli schema is rendered by hand in `gorilla_proto::malli_schema()` | The examples cross-check catches drift in field names and types, but a derive (or `schemars`) would remove the hand-written half | Any |
| A retriggered sample restarts without a fade | Can click on long samples | 8 |
| Decoded samples are never evicted from the cache | Memory grows if you swap through many samples | 8 |
| The engine starts at 120 BPM until a brain sends the song | Only matters before the first connect | Any |
| `symphonia` is MPL-2.0 | Not banned (file-level copyleft), but not MIT/Apache either; `hound` (Apache-2.0) covers WAV if that ever matters | Any |
| A live pattern stopped for running over budget may keep a thread busy | The JVM can't kill a thread; pure code that never yields runs until it finishes | Any |
| Each channel plays one note at a time | No chords on one channel yet; use one channel per voice | 8 |
| Sampler root pitch is fixed at 60 | A sample recorded at another pitch plays out of tune with synths | 8 |
| The playhead reports bars since play; the UI derives the arrangement position from the song | Works while brain and engine agree on the playlist | Any |
| Telemetry is a socket stream, not shared memory | Enough for the playhead; maybe not for scopes and spectra | 8 |
| Auto-restart runs `cargo run` from `../engine` | Works only from a dev checkout; a packaged app needs a bundled engine binary | Packaging |
| The UI edits steps and notes per step, but has no piano roll (pitch, length) or drag to resize clips | Notes keep the pitch of the last note; clip length is fixed at click time | UI follow-up |
| No native file dialogs (HumbleUI has none) | Save and Open work with `brain/songs/`; other folders need the REPL | UI follow-up |
| HumbleUI is alpha and pinned to a commit | Updating it may need code changes | Any |
| `msgpack-core` needs `--sun-misc-unsafe-memory-access=allow` on JDK 24+ | A future JDK may remove the API; a small built-in encoder would drop the dependency | Any |
| One brain connection at a time, with no authentication | Must stay on loopback; agents go through the brain (MCP), not the socket | 9 |
