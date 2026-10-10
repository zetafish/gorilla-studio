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
(start!)            ; connect and send the song
(toggle! :hat 3)    ; heard on the next audio buffer
(undo!)
(stop!)             ; the engine keeps playing
```

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
