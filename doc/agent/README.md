# doc/agent/

The guides and the numbers behind the Claude Code harness in `.claude/`.

| File | Content | Read by |
| --- | --- | --- |
| `architecture.md` | the architecture as it is: areas, the core/adapter seam, the patterns with their names (typed ids, `NamespaceRouted`, `RuntimeFeature`, installers) | `/refine`, the architecture reviewer, every plan |
| `quality-catalog.md` | the numbered rules: D-rules the build enforces, I-rules the reviewers judge | reviewers, `/tidy`, humans adding a rule |
| `naming.md` | good and bad names from this repository, as examples | the readability reviewer, `/tidy` |
| `testing.md` | how tests are written here, characterization tests, what is not mocked | the test reviewer, `/refine` |
| `review-policy.yml` | paths that always need a human reviewer, paths where evidence is enough | `/inspect` |
| `baseline.md` | the measured state: violations per rule and module, largest classes, open gaps; rewritten by the ratchet | the weekly review |

Until these files exist, the rules are the "Code Conventions" section of
`CLAUDE.md`. They move here, and `CLAUDE.md` imports them, in a later step.

There is deliberately no code formatter in the build. `.editorconfig` carries
the shared settings; whether a formatter is needed is decided after the first
cleanup waves, when diffs show whether formatting noise is a problem.

Where to add a rule when an agent keeps doing something wrong:

- Mechanically checkable (an import, a name pattern, a size): an ArchUnit or
  PMD rule in the build, plus a D-line in `quality-catalog.md`.
- A matter of judgement (a name that says the wrong thing, a comment that
  restates code): an I-line in `quality-catalog.md` plus an example in
  `naming.md`.
- A pattern the agent reinvents: a paragraph in `architecture.md`.
- Something that must never or always happen: a hook in `.claude/settings.json`.
- A wrong assumption made during refine: a decision in `doc/decisions/`.
- Wrong in every session before any skill runs: one line under hard rules in
  `CLAUDE.md`, and ask which line can go to make room.
