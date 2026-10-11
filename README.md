# Gorilla Studio

A pattern-based music workstation in the spirit of FL Studio, built the way Overtone drives SuperCollider:

- **Brain** (Clojure): owns what the music *is*. The song is one EDN value; editing, undo, UI, live coding and agent access all work on that value.
- **Engine** (Rust): owns what it *sounds like*. Sample-accurate clock, real-time DSP, plugin hosting, disk streaming, offline export.

The two run as separate processes. The engine plays its own copy of the song and never waits on the brain, so a garbage-collection pause or a REPL reload never makes a click.

See [`docs/architecture.md`](docs/architecture.md) for the design, [`docs/roadmap.md`](docs/roadmap.md) for milestones and known gaps, and [`CLAUDE.md`](CLAUDE.md) for the rules every change follows.

## Status

Milestones 1–6 are done: the two-process slice, telemetry with resync, live patterns, an engine driven by the song (channels, samples, tempo, graph swaps), saving and opening songs, and melody with an arrangement. Next is milestone 7, mixer and effects. Details in the [roadmap](docs/roadmap.md).

## Try it

```sh
cd brain && clojure -M:nrepl     # the brain starts the engine itself on (connect!)
```

Then, in the REPL (helpers live in `brain/dev/user.clj`):

```clojure
(connect!)          ; stay connected; starts the engine if needed, restarts it if it dies
(toggle! :hat 3)    ; heard on the next audio buffer
(undo!)
(stop!) (play!)     ; transport; play restarts from the top
(playhead)          ; {:bar 12 :step 3 :playing true :epoch 41 ...}

(defpattern :hat (fn [bar] (euclid (+ 3 (mod bar 4)) 16)))
(defpattern :kick (fn [bar] (every 2 bar "x..x..x...x.x..." "x...x...x...x...")))
(defpattern :snare (fn [bar] (overlay "....x.......x..." (chance 0.15 bar))))
(patterns)          ; what's live, with source
(unpattern :hat)    ; back to the song's row

(bpm! 96)
(channel! :pop "/System/Library/Sounds/Pop.aiff")   ; a sampler channel
(row! :pop "....x.......x..x")                       ; a row playing it, in :drums
(row! :fills :pop "xxxx............")               ; a second pattern, looping too
(remove-channel! :pop)
(rejection)         ; the engine's last refusal, if any

(require 'songs.night :reload) (songs.night/start!)   ; arranged, melodic
(channel! :bass :bass)                                ; built-in synths: :bass, :lead
(notes! :low :bass (rhythm [1/4 1/8 1/8 1/2] [:a1 :a1 :c2 :e2]))
(defpattern :low :bass
  (fn [bar] (rhythm [1/8] (markov-walk {:seed bar :len 8 :scale (scale :a1 :minor)}))))
(arrange! [[:drums 0 8] [:low 4 12]]) (loop! true)    ; [pattern start-bar bars]

(save! "songs/my-beat")   ; a folder: song.edn plus assets/
(open! "songs/my-beat")   ; live patterns come back frozen
(activate!)               ; run their saved source again, sandboxed
(new-song!)

(disconnect!)       ; the engine keeps playing what it has
(reload!)           ; after code changes, keeps the song
```

To run the engine yourself instead: `cd engine && cargo run -p gorilla-rt` (listens on `127.0.0.1:7878`); `(connect!)` then uses it.

## Development

The repo is colocated jj + git. After cloning, run `./scripts/setup-jj.sh` once; the workflow is in [`CLAUDE.md`](CLAUDE.md#version-control-jj-on-top-of-git).

```sh
# Engine (Rust workspace)
cd engine && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
cargo run -p gorilla-rt          # the gorilla-engine binary

# Brain (Clojure)
cd brain && clojure -M:test      # run tests
clojure -M:nrepl                 # start an nREPL server (writes .nrepl-port)
```

The engine workspace denies `unwrap`, `expect`, `panic!`, `todo!`, `unreachable!`, slice indexing and `unsafe` through workspace lints, so the "never crash" rules are checked by clippy.
