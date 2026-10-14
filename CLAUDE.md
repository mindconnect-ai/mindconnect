# CLAUDE.md

@AGENTS.md

## Claude Code here

- The harness lives in `.claude/` and is committed: shared permissions and
  hooks in `settings.json`, repository skills in `skills/`, reviewer subagents
  in `agents/`. Cross-repository skills (branch clones, merge to `next`, todos)
  come from the `mc-tooling` plugin.
- Read `doc/agent/architecture.md` before planning a change that crosses a
  module; read the decisions of the area in `doc/decisions/`.
- For a feature or a refactoring, work against `doc/specs/<branch>.md` and
  keep `doc/specs/<branch>.progress.md` current (status, next step, one log
  line) before every stop; `doc/specs/README.md` has the format.
- When compacting, preserve the list of touched modules, the verify command
  and any open decision for the maintainer.
