# doc/agent/

## Status: parked

Parked on 2026-10-15. This branch holds the full harness: the spec loop
(`/refine`, progress files, `/inspect`), four reviewer subagents, three hooks,
PMD and Jacoco against a frozen baseline, `scripts/baseline.sh` and the
numbered quality catalog. It turned out too heavy to start with.

Work continues on `chore/harness-minimal`, which keeps only what a cleanup
needs: the ArchUnit rules with their frozen stores, one conventions page plus
`architecture.md` and the decisions, and a `/tidy` skill that cleans up one
module at a time. Pieces of this branch come back one by one, when the
minimal set shows that they are missing.

| Parked here | Comes back when |
| --- | --- |
| hooks (`protect-paths`, `after-edit`, `before-stop`) | agents keep stopping with a red build or edit the rules |
| PMD, Jacoco, `scripts/baseline.sh`, `baseline.md`, `.github/scripts/` | ArchUnit plus `/tidy` leave method size, comments or duplication unchecked |
| `quality-catalog.md` (D/I numbering), `testing.md`, `review-policy.yml` | several reviewers need to cite the same rules |
| reviewer subagents, `/inspect`, `/refine`, `/gc`, `/gap`, `/arch-check` | features are built against the harness, not only cleanups |
| `doc/specs/` and progress files | a change spans more than one session |
| `launch.template.json`, `mc.env.example`, `after-branch-creation.sh` changes | the per-clone preview ports are needed again |

The guides and the numbers behind the Claude Code harness in `.claude/`.

| File | Content | Read by |
| --- | --- | --- |
| `architecture.md` | the architecture as it is: areas, layout, the core/adapter seam, the patterns with their names (typed ids, `NamespaceRouted`, `RuntimeFeature`, installers) | `/refine`, the architecture reviewer, every plan |
| `conventions.md` | the Code Conventions as the maintainer wrote them; the prose behind the catalog's numbers | reviewers, humans |
| `quality-catalog.md` | the numbered rules: D-rules the build enforces, I-rules the reviewers judge | reviewers, `/tidy`, humans adding a rule |
| `naming.md` | good and bad names from this repository, as examples | the readability reviewer, `/tidy` |
| `testing.md` | how tests are written here, characterization tests, what is not mocked | the test reviewer, `/refine` |
| `review-policy.yml` | paths that always need a human reviewer, paths where evidence is enough | `/inspect` |
| `baseline.md` | the measured state: violations per rule and module, largest classes, open gaps; rewritten by the ratchet | the weekly review |

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
