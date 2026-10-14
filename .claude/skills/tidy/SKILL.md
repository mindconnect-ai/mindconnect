---
name: tidy
description: Behaviour-preserving cleanup of the files this branch touched — names, extractions, comments, redundancy — committed on its own as "tidy:". Run after implementing, before /inspect.
disable-model-invocation: true
---
Tidy the files changed on this branch against `origin/main` (or the files in $ARGUMENTS). Behaviour does not change.

1. `./scripts/verify.sh` on the touched modules. Green before you start, or stop and say so.
2. Note the `@Test` count per touched module.
3. For every touched main source file, against `doc/agent/quality-catalog.md` I1–I5 and `doc/agent/naming.md`:
   - rename what says how instead of what, what is a `Helper`/`Util`/`Manager`/plural, what starts with `process`/`handle`/`do`, what has a digit suffix;
   - extract methods over 50 lines into named steps; if a class needs "and" to be described, split it, but only inside the files you touched;
   - delete comments that restate the code, tell history or reference a concept; keep the ones that say why or state an invariant;
   - remove null checks, try/catch and fallbacks that cannot trigger; keep `catch (Exception ignored)` only with a comment saying what is ignored and why it cannot matter;
   - replace inline fully qualified names with imports.
4. Do not add abstractions, interfaces, parameters, configuration or tests. Do not touch files outside the branch. Do not change test sources except to follow a rename.
5. `./scripts/verify.sh` again: green, same `@Test` count. PMD and ArchUnit must not gain a violation; if a rename removes a frozen one, say so (the store is refreshed in a ratchet step by a human, not by you).
6. Commit as `tidy: <what, in five words>`, separate from the behaviour commits. Update the progress file (Status `tidying` → `inspecting`, one log line).

Report what you renamed, extracted and deleted as a list. If there was nothing to tidy, say so in one line.
