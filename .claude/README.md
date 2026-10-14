# .claude

The Claude Code harness of this repository. It is committed so that every clone,
every branch folder and every contributor runs the same skills, reviewers and hooks.

| Path | What | Committed |
| --- | --- | --- |
| `settings.json` | shared permissions and hooks | yes |
| `settings.local.json` | personal overrides | no |
| `launch.template.json` | template for the preview servers of a clone | yes |
| `launch.json` | generated per clone by `after-branch-creation.sh` | no |
| `skills/` | repository workflows: `/refine`, `/tidy`, `/inspect`, `/arch-check`, `/gc`, `/gap` | yes |
| `agents/` | reviewer subagents in a fresh context: spec, architecture, readability, test | yes |
| `hooks/` | `protect-paths.sh` (PreToolUse: the harness is edited by humans), `after-edit.sh` (PostToolUse: compile, comment ratio), `before-stop.sh` (Stop: verify.sh, test count, progress file) | yes |

Cross-repository skills (branch clones, merge to `next`, todos) live in the
`mc-tooling` plugin, not here. The rules the reviewers apply are in
`doc/agent/`; the hard rules every session needs are in `CLAUDE.md`.

## The loop

```
/refine  →  plan  →  implement  →  /tidy  →  /inspect  →  PR
 SPEC +      (plan     hooks run      own        4 reviewers,
 progress     mode)    on every       commit     evidence in
 file                  edit and stop             the PR body
```

`before-stop.sh` keeps an agent from saying "done" while `./scripts/verify.sh`
is red for a touched module, a test disappeared, or the branch's progress
file is stale. Set `MC_ALLOW_HARNESS_EDIT=1` for a session that works on the
harness itself; otherwise `protect-paths.sh` refuses edits to rules, baselines,
hooks and the review policy.
