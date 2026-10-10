# Gorilla Studio: instructions for agents

A pattern-based DAW: a Clojure **brain** (song model, commands, undo, UI, live coding, agent access) and a Rust **engine** (real-time audio, DSP, plugins), running as two processes. Full design: [`docs/architecture.md`](docs/architecture.md). Read it before changing anything structural.

## Start every session here
1. Read `README.md` for the current milestone.
2. Read the section of `docs/architecture.md` that covers what you're touching.
3. If a change contradicts the architecture, update `docs/architecture.md` in the same change and say why.

## Non-negotiables

### Real-time audio thread (engine/rt)
- No allocation, no locks that can block, no I/O, no syscalls, no logging on the audio thread. Use `assert_no_alloc` in debug builds and keep it passing.
- Data reaches the audio thread only through lock-free structures (`rtrb` ring buffers, atomic pointer swaps). Graphs are built on the control thread and swapped in at a buffer boundary; old graphs are dropped on the reclaim thread.
- Parameter changes go through per-parameter queues with smoothing; they never trigger a graph rebuild.

### Never crash
- Engine code has no `unwrap()`, `expect()`, `panic!`, `unreachable!`, `todo!` or indexing on input-derived data. Return `Result`, use `get()`, checked or saturating arithmetic.
- `unsafe` is allowed only in `engine/host` (plugin hosting), and every block has a `// SAFETY:` comment.
- Third-party plugins run in their own sandbox processes. A plugin crash bypasses its slot; it never takes down the engine.
- Every crash fix gets a regression test.

### The boundary
- `engine/proto` is the single source of truth for patches and telemetry. Rust types there generate the malli schemas the brain uses; never hand-edit generated schemas.
- Patches are validated twice: malli in the brain, serde types in the engine. Invalid input is rejected and current state stays untouched.
- Every patch carries an epoch so either side can resync from a full snapshot.

### Brain (Clojure)
- The song is one immutable EDN value in an atom. Undo is a history of song values.
- Every user-visible action is a command: id, malli params schema, label, and a pure `(fn [song params] -> song)`. UI, REPL, MCP and shortcuts all go through `dispatch`.
- The brain never schedules notes in real time. Live patterns are pure functions of the bar number, evaluated about 2 bars ahead and written into the song as concrete notes.
- Saved songs contain only data. Live pattern source is kept as a string and is never evaluated on load. In-song expressions run in SCI with no I/O.
- Shared code (song model, commands, differ, patterns) lives in `.cljc` so the web build can reuse it.

### Licensing
- No GPL/LGPL/AGPL code or dependencies (this rules out JUCE under AGPL). Prefer MIT/Apache-2.0/BSD. CLAP is the primary plugin format.

## Tooling
- Clojure: evaluate through nREPL with `clj-nrepl-eval`; use `:reload` when requiring changed namespaces. Fix unbalanced delimiters with `clj-paren-repair`, never by hand.
- Rust: `cargo fmt`, `cargo clippy -- -D warnings` and `cargo test` must pass before every commit.
- Commits: one logical change each, only from green states.

## Version control: jj on top of git
The repo is a colocated jj + git repo. Use `jj` for everything that changes history; plain `git` is fine for reading (`git log`, `git blame`), but never `git commit`, `git checkout`, `git rebase` or `git stash`. Git shows a detached HEAD; that's normal.

One-time setup after cloning: `jj git clone --colocate <url>` (or `jj git init --colocate` in an existing clone), then `./scripts/setup-jj.sh`. It sets a 2 MiB limit on auto-tracked new files, `jj fix` formatters (rustfmt for `engine/`, cljfmt for `brain/`) and a `wip` revset alias.

Workflow:
1. Start each logical change with a description: `jj new -m "engine: play hard-coded drum pattern"`. Prefix with the area: `engine:`, `brain:`, `proto:`, `docs:`, `build:`.
2. Work. jj snapshots the working copy on every command, so there is no staging step and nothing to stash.
3. Before finishing a change: `jj fix`, then the checks (`cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` in `engine/`; `clojure -M:test` in `brain/`). jj runs no git hooks, so nothing does this for you.
4. If the change grew past one logical step, split it non-interactively by path: `jj split -m "proto: add SetSteps patch" engine/proto`. Fold a fix into an earlier change with `jj squash -u --into <rev> <paths>` (`-u` keeps the destination's description instead of opening an editor).
5. Start the next change with `jj new -m "..."`.

Rules for agents:
- Don't run interactive commands (`jj split` / `jj squash` / `jj commit` without paths or `-m`, `jj resolve`, `jj diffedit`): they open an editor or diff tool and hang. Always pass `-m` and paths.
- Run `jj st` before and after a batch of edits; `jj log -r wip` shows the current stack.
- Every file in the working copy is tracked automatically. Before creating scratch files, generated output or audio in the repo, make sure `.gitignore` covers it; untrack strays with `jj file untrack <path>`.
- If something goes wrong, `jj op log` and `jj undo` restore the previous state. Prefer them to hand repair.
- Don't push, move the `main` bookmark or edit changes already on `main@origin` unless asked. Publishing is `jj bookmark set main -r @-` then `jj git push`.
