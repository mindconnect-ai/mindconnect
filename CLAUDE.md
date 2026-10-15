# CLAUDE.md

@AGENTS.md

## Claude Code here

- `.claude/` is committed: shared permissions in `settings.json` and the
  repository skills: `/verify` (tests and ArchUnit of the touched modules),
  `/tidy-inspect` (check a module, read-only), `/tidy` (clean up one module,
  behaviour unchanged), `/gap` (record a finding), `/rule` (add or sharpen a
  convention), `/adr` (record a decision). Cross-repository skills
  (branch clones, merge to `next`, todos) live outside this repository.
- Read `doc/agent/conventions.md` before writing code and
  `doc/agent/architecture.md` before a change that crosses a module; read the
  decisions of the area in `doc/decisions/`.
- When compacting, preserve the list of touched modules, the verify command
  and any open decision for the maintainer.
