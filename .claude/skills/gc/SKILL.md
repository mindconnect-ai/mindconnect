---
name: gc
description: Weekly garbage collection — close up to three small known gaps as tidy branches with PRs, scan for drift (new large classes, duplicates, new frozen violations), and write the week's numbers to doc/agent/baseline.md. Run from a routine on a fresh clone of main, or by hand.
disable-model-invocation: true
---
You are on a clean checkout of `main` (verify with `git status` and `git log -1`; if not, stop). Budget: `$ARGUMENTS` or three gaps.

1. **Pick.** From `doc/known-gaps.md` "Open", take the oldest entries with `Size: small` and no `Blocks:`; skip any whose module matches an `always-human` path in `doc/agent/review-policy.yml`.
2. **For each gap, its own branch** `tidy/gap-<slug>` from `main`:
   - if the module has no tests for the class, write a characterization test first;
   - fix exactly the gap, nothing else; `./scripts/verify.sh <module>`; commit `tidy: <gap in five words>`;
   - run `/inspect spec architecture` with the gap entry as the SPEC;
   - open a PR with `gh pr create --label tidy --label no-changelog`, body = the gap line, the verify output, the reviewer status;
   - leave the entry open; it is ticked when the PR merges.
3. **Drift.** Back on `main`: `./scripts/baseline.sh`. Compare with the previous `doc/agent/baseline.md` (git show HEAD:doc/agent/baseline.md): classes that crossed 300 lines since last week, a larger ArchUnit store, more PMD pairs, methods with identical bodies across modules (`grep` for duplicated signatures is enough as a hint), interfaces outside `port` packages with exactly one implementation. Append what you find to `doc/known-gaps.md` as new entries (size by your estimate, rule by the catalog). Never delete, close or resize an existing entry.
4. **Stale.** Mark entries older than four weeks with `(stale)` after the date; the maintainer decides.
5. **Report**, in the chat and as the last section of `doc/agent/baseline.md` ("## This week"): gaps closed by PR (with numbers), gaps found, gaps left and why, stale entries. Commit the baseline and gap updates on a branch `chore/gc-<date>` with a PR, label `no-changelog`.

You never merge, never edit rules, stores or baselines in `mc-java-parent/` or `archunit_store/`, never touch `always-human` paths.
