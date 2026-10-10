# Gorilla Studio

A pattern-based music workstation in the spirit of FL Studio, built the way Overtone drives SuperCollider:

- **Brain** (Clojure): owns what the music *is*. The song is one EDN value; editing, undo, UI, live coding and agent access all work on that value.
- **Engine** (Rust): owns what it *sounds like*. Sample-accurate clock, real-time DSP, plugin hosting, disk streaming, offline export.

The two run as separate processes. The engine plays its own copy of the song and never waits on the brain, so a garbage-collection pause or a REPL reload never makes a click.

Status: design stage. See [`docs/architecture.md`](docs/architecture.md) for the full design and [`CLAUDE.md`](CLAUDE.md) for the rules every change follows.

## First milestone: the vertical slice

Prove the two-process design end to end before building out the layout:

1. Rust engine plays a hard-coded 16-step drum pattern through `cpal`.
2. One patch type, `[:pattern/set-steps pattern row steps]`, arrives over a local socket and is applied at a buffer boundary.
3. A Clojure REPL holds the song in an atom; toggling a step diffs the song and sends the patch while audio keeps playing.

Done when you can toggle hi-hat steps from the REPL with no audible glitch, and killing the brain process leaves the beat playing.

Status: implemented. To try it:

```sh
cd engine && cargo run -p gorilla-rt     # plays the default beat, listens on 127.0.0.1:7878
cd brain && clojure -M:nrepl             # in another terminal
```

Then, in the REPL (helpers live in `brain/dev/user.clj`):

```clojure
(connect!)          ; stay connected; starts the engine if needed, restarts it if it dies
(toggle! :hat 3)    ; heard on the next audio buffer
(undo!)
(stop!) (play!)     ; transport; play restarts from the top
(playhead)          ; {:bar 12 :step 3 :playing true :epoch 41 ...}
(disconnect!)       ; the engine keeps playing what it has
(reload!)           ; after code changes, keeps the song
```

## Third milestone: live patterns

Status: implemented. Rows can be functions of the bar number, evaluated two bars ahead and queued in the engine per bar:

```clojure
(defpattern :hat (fn [bar] (euclid (+ 3 (mod bar 4)) 16)))
(defpattern :kick (fn [bar] (every 2 bar "x..x..x...x.x..." "x...x...x...x...")))
(defpattern :snare (fn [bar] (overlay "....x.......x..." (chance 0.15 bar))))
(patterns)          ; what's live, with source
(unpattern :hat)    ; back to the song's row
```

Generators live in `gorilla.pattern`: `steps` (also parses `"x..x"` strings), `euclid`, `rotate`, `invert`, `overlay`, `mask`, `chance`, `every`. Redefining a pattern lands on the next bar line.

## Second milestone: the engine talks back

Status: implemented. The engine says hello on connect (a random engine id and its epoch) and streams the playhead at about 60 Hz on the same socket. The brain uses the hello to send only what a reconnecting engine is missing, and `connect!` keeps the link up, restarting a crashed engine with `cargo run`.

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
