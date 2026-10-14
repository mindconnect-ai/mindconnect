---
name: inspect
description: Independent review of this branch by the four reviewer subagents in fresh contexts, fixes for their findings, recorded gaps, and a PR body with the evidence. Run after /tidy, before opening or updating the PR.
disable-model-invocation: true
---
Review this branch against `origin/main` with subagents that see only the diff, the SPEC and the catalog. $ARGUMENTS may name a subset: `spec`, `architecture`, `readability`, `test` (default: all four).

1. **Evidence first.** Run `./scripts/verify.sh` on the touched modules and keep the output. If it is red, stop: there is nothing to review yet.
2. **Reviewers.** Launch the chosen subagents from `.claude/agents/` in parallel: spec-reviewer, architecture-reviewer, readability-reviewer, test-reviewer. Tell each: the branch, the SPEC path `doc/specs/<branch-slug>.md`, and that findings must cite a rule number. Collect their two lists ("fix now", "record").
3. **Fix now.** Apply the "fix now" findings in this session. Push back on a finding that is a style preference or proposes an abstraction, defensive code or a test for an impossible case: those are not fixes (I4). Then re-run the reviewer that raised the findings in *verifier mode*: "Are these findings resolved? Do not look for new ones." At most two rounds; what remains open goes to the maintainer as "needs decision".
4. **Record.** Append the "record" findings to `doc/known-gaps.md` in its format, deduplicated against existing entries.
5. **Risk tier.** Match the changed paths against `doc/agent/review-policy.yml`; the first matching tier wins, `always-human` beats the rest.
6. **PR body.** Write (and, if `gh pr view` shows an open PR, update with `gh pr edit --body-file`) this body:

```
## Spec
doc/specs/<slug>.md

## Evidence
- verify.sh <module>: <n> tests (+<delta> vs main), ArchUnit 0 new, PMD 0 new
- (one line per touched module)
- Manual: <case> PASS / not applicable
- Reviewers: spec ✓ / architecture ✓ / readability ✓ (<n> fixes applied) / tests ✓
- Needs decision: <open findings, or none>

## Risk tier
<always-human | evidence | light> (<matching path or rule>)

## Public API
<added/changed public types and methods, or "none">

## Recorded gaps
<entries added to doc/known-gaps.md, or "none">
```

7. Update the progress file: Status `ready` (or `blocked` with the open decision), Next = "merge" or the decision, one log line.

If the SPEC says `no-changelog` applies (refactoring, tests, docs, build), say so in the PR body's first line so the label is set.
