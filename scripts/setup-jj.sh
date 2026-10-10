#!/bin/sh
# Per-repo jj settings. jj keeps repo config outside the working copy, so run
# this once after `jj git init --colocate` (or `jj git clone --colocate`).
set -eu

jj config set --repo snapshot.max-new-file-size 2MiB

jj config set --repo fix.tools.rustfmt.command '["rustfmt", "--edition", "2024", "--emit", "stdout"]'
jj config set --repo fix.tools.rustfmt.patterns '["glob:engine/**/*.rs"]'

jj config set --repo fix.tools.cljfmt.command '["cljfmt", "fix", "-"]'
jj config set --repo fix.tools.cljfmt.patterns '["glob:brain/**/*.clj", "glob:brain/**/*.cljc", "glob:brain/**/*.cljs", "glob:brain/**/*.edn"]'

jj config set --repo 'revset-aliases."wip"' '"trunk()..@"'

echo "jj configured: $(jj config path --repo)"
