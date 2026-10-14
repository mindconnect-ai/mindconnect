# .claude

The Claude Code harness of this repository. It is committed so that every clone,
every branch folder and every contributor runs the same skills, reviewers and hooks.

| Path | What | Committed |
| --- | --- | --- |
| `settings.json` | shared permissions and hooks | yes |
| `settings.local.json` | personal overrides | no |
| `launch.template.json` | template for the preview servers of a clone | yes |
| `launch.json` | generated per clone by `after-branch-creation.sh` | no |
| `skills/` | repository workflows: refine, tidy, inspect, arch-check, gc | yes (filled in a later step) |
| `agents/` | reviewer subagents that run in a fresh context | yes (filled in a later step) |
| `hooks/` | scripts behind the hooks in `settings.json` | yes (filled in a later step) |

Cross-repository skills (branch clones, merge to `next`, todos) live in the
`mc-tooling` plugin, not here. The rules the reviewers apply are in
`doc/agent/`; the hard rules every session needs are in `CLAUDE.md`.
