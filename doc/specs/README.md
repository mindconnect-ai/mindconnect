Wie # Specs

One SPEC per branch, written by `/refine` before implementation starts,
corrected by a human, read by the implementing session and by the reviewers.
Next to it the branch's progress file, written by the agent, read by the
human who switches between sessions.

```
doc/specs/feat-mail-search.md            what this branch builds (stable)
doc/specs/feat-mail-search.progress.md   where it stands (volatile, removed on merge)
```

## SPEC

```markdown
---
id: feat/mail-search
title: Mail search over the window index
touches: [agents/office/mc-mail-index, agents/office/mc-mail-views]
depends-on: []
blocks: []
risk: standard
source: mc-sandbox/todos/2026-09-12-mail-suche.md
---

## Behaviour
What changes for a user or a caller, in sentences.

## Out of scope
What is deliberately not done, so nobody adds it in passing.

## Touches
Modules and the classes expected to change. Changes outside this list are a review finding.

## Acceptance
1. Numbered criteria, each with how it is checked (unit, integration, manual case).

## Verify
./scripts/verify.sh <module> [<module>…]
```

A SPEC names at least one check that can be run. It stays in the repository
after the merge as the record of what was wanted; what applies permanently
becomes a decision or documentation.

## Progress

```markdown
# feat/mail-search — progress

**Status:** implementing (3 of 4 acceptance criteria green) · 2026-10-14 16:40
**Next:** acceptance 4 — manual case mail/search-in-window against port 9317; then /tidy
**Needs David:** wording of coverage in the tool description
**Blocked:** —

## Done
- [x] 1 …
- [ ] 4 …

## Log (newest first)
- 16:40 verify.sh green on mc-mail-index, mc-mail-views. +9 tests.

## Context for a fresh session
- Works: ./scripts/verify.sh agents/office/mc-mail-index
- Pitfall: …
```

Status is one of `refining`, `planned`, `implementing`, `tidying`,
`inspecting`, `ready`, `blocked`. `Next` is one sentence a fresh session can
act on. The agent updates the file before every stop; the merge removes it.
