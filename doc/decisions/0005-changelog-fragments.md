# 0005: User-facing changes are recorded as changelog fragments, never in CHANGELOG.md

Status: accepted · 2026-09 · See changelog.d/README.md

## Decision
A user-facing change adds `changelog.d/<section>/<branch-slug>.md` holding
one bullet; the release workflow assembles the fragments into `CHANGELOG.md`
under the version being cut. Nobody edits `CHANGELOG.md` directly. A PR that
touches shipped Java without a fragment fails the `changelog` check unless
labelled `no-changelog`.

## Why
Every open PR appending at the same spot of one file put every other PR in
conflict. Fragments merge without conflict and are written for a reader
deciding whether to upgrade, not for the commit log.

## Consequences
Refactorings, tests, docs and build plumbing need no fragment; label the PR.
Tidy branches are labelled `no-changelog` by default.
