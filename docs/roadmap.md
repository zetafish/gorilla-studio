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
| 4 | The song drives the engine | **Next** |
| 5 | Save and load | Planned |
| 6 | Melody and arrangement | Planned |
| 7 | Mixer and effects | Planned |
| 8 | Desktop UI | Planned |
| 9 | Agent access (MCP) | Planned |
| 10 | Offline export | Planned |
| 11 | Plugin hosting | Planned |
| 12 | Web target | Planned |

The order after milestone 6 is flexible. Agent access (9) is cheap because every action is already a command, so it can move earlier whenever it's useful.

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

## Next

### 4. The song drives the engine

Today the engine knows one pattern (`:drums`) with three built-in voices at a fixed 120 BPM. After this milestone, the engine plays whatever the song describes.

- **Tempo:** `:song/bpm` reaches the engine as a patch (`:song/set-bpm` command). Changing it mid-play keeps the bar position, without a jump or click.
- **Any patterns and rows:** the engine stops hard-coding `drums/kick/snare/hat`. Patterns and rows in the song become engine state built on the control thread.
- **Channels and generators:** each row plays a channel; a channel has a generator, either `[:sampler {:sample "kick.wav"}]` or a built-in synth (today's drum voices become built-ins).
- **Sample playback:** decode with `symphonia` into a sample cache, off the audio thread.
- **Graph swaps:** adding or removing channels builds a new graph on the control thread and swaps it in at a buffer boundary; a reclaim thread drops the old one. The first real use of the design in [architecture §4](architecture.md#4-engine-threads-and-the-real-time-rules).
- **Close gaps:** transport restored after an engine restart; the engine reports rejected patches so the brain can resync; schema generation scales to many patch types.

**Done when:** a song with two patterns and sample-based rows plays at song tempo; adding a channel while playing makes no click; changing BPM mid-play keeps the groove; a rejected patch shows up in the brain.

## Planned

### 5. Save and load

- A song file: the song value written out as EDN, plus an `assets/` folder for samples.
- Live patterns saved as materialized output, with their source kept as a string (`defpattern` already records it). Opening a song never evaluates code.
- In-song expressions run in SCI with no I/O.

**Done when:** save, quit both processes, open again, and it sounds the same; a file with live-pattern source opens without running it; the file diffs cleanly in git.

### 6. Melody and arrangement

- Note patterns: `{:t :len :pitch :vel}` notes alongside step rows, with pitched generators.
- Melodic live helpers: `scale`, `markov-walk`, `rhythm`.
- The playlist: patterns placed on tracks over bars, and the engine plays the arrangement, not just loops.
- Live patterns run with a time budget, so a slow pattern can't hold up the others.

**Done when:** a live melodic pattern plays in key over a drum loop; an arrangement of several patterns plays start to finish.

### 7. Mixer and effects

- Mixer channels with volume, pan, sends and an effect chain (`fundsp`).
- Parameter changes through per-parameter lock-free queues with smoothing, without graph rebuilds.
- Meters in telemetry. Move telemetry to shared memory if meters and scopes need the bandwidth.

**Done when:** sweeping a filter cutoff from the REPL is smooth (no zipper noise); adding an effect while playing makes no click.

### 8. Desktop UI

- HumbleUI: step sequencer, playlist and mixer as custom-drawn canvases. The UI reads the song atom and dispatches commands; it is never the source of truth.
- The playhead and meters come from telemetry.
- Packaging: the brain launches a bundled engine binary instead of `cargo run`.

**Done when:** a beat can be built and arranged without the REPL, and every UI action can be undone.

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
| A restarted engine always starts playing; transport state isn't restored | After a crash it plays even if you had stopped | 4 |
| The brain assumes the engine applied everything it sent | A rejected patch (e.g. an unknown pattern) leaves the two sides out of sync, silently | 4 |
| The engine only knows `:drums` with kick, snare and hat; a snapshot with any other pattern is rejected whole | Blocks real songs | 4 |
| Tempo is fixed at 120 BPM; `:song/bpm` isn't sent | | 4 |
| The malli schema is rendered by hand in `gorilla_proto::malli_schema()` | Fine for four patch types; won't scale | 4 |
| Live patterns exist only in the REPL session | Lost on restart | 5 |
| A slow live pattern delays the others (they share the scheduler thread) | Two bars of lookahead hide it for now | 6 |
| Telemetry is a socket stream, not shared memory | Enough for the playhead; maybe not for scopes and spectra | 7 |
| Auto-restart runs `cargo run` from `../engine` | Works only from a dev checkout | 8 |
| `msgpack-core` needs `--sun-misc-unsafe-memory-access=allow` on JDK 24+ | A future JDK may remove the API; a small built-in encoder would drop the dependency | Any |
| One brain connection at a time, with no authentication | Must stay on loopback; agents go through the brain (MCP), not the socket | 9 |
