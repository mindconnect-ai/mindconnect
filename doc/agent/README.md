# doc/agent/

The rules behind the Claude Code harness in `.claude/`.

## Status

Started 2026-10-15 as a deliberately small harness for one job: cleaning up
code that is redundant, badly named, off the module structure, has too many
responsibilities per class, or carries Javadoc full of history. It has three
parts:

1. **ArchUnit, frozen.** The rules in `common/mc-arch-rules` run in each area's
   arch-tests module; today's violations are recorded and tolerated, a new one
   fails the build, and the store only shrinks. The store size is the measure
   of progress.
2. **One conventions page plus the map.** `conventions.md` says how code is
   written here, with examples; `architecture.md` says what already exists, so
   an agent can look left and right; `../decisions/` holds what was decided.
3. **Skills.** `/verify` runs the tests and ArchUnit of the touched modules; `/smells <module>` checks a module against the conventions and
   reports, read-only; `/tidy <module>` cleans one module or package up without
   changing behaviour, one topic per commit; `/gap` records a finding outside
   the scope in `../known-gaps.md`; `/rule` adds or sharpens a criterion, as an
   ArchUnit rule when a tool can decide it; `/adr` records a decision.

Next: the first `/tidy` run on a small `-core` module, then decide from its
diff what is missing. The fuller harness (hooks, reviewer subagents, a spec
loop, PMD, Jacoco, a numbered quality catalog) is parked on the branch
`chore/harness-skeleton`; its `doc/agent/README.md` says when each piece
comes back.

| File | Content | Read by |
| --- | --- | --- |
| `conventions.md` | how code is written here, with good and bad examples; what the build checks | every session that writes code, `/tidy` |
| `architecture.md` | the architecture as it is: areas, layout, the core/adapter seam, the patterns with a name | every plan that crosses a module, `/tidy` |

There is deliberately no code formatter in the build; see
`../decisions/0006-no-formatter-editorconfig.md`.

## Where to add a rule

When an agent keeps doing something wrong:

- Mechanically checkable (an import, a name pattern, a size): `/rule` makes it
  an ArchUnit rule in `McArchRules`, switched on frozen, plus a line in the
  last table of `conventions.md`.
- A matter of judgement (a name that says the wrong thing, a comment that
  restates code): `/rule` adds a line and an example to `conventions.md`.
- A pattern the agent reinvents: a paragraph in `architecture.md`.
- A wrong assumption about the design: `/adr` records a decision in
  `../decisions/`.
- Wrong in every session: one line under hard rules in `AGENTS.md`, and ask
  which line can go to make room.
