# mindconnect

Maven monorepo for building LLM-powered agents. Java 21; the deployable apps use
Spring Boot 3.5, the libraries are plain Java. Packages: `ai.mindconnect.*`.

Four areas, each usable without the others; only `common/` is shared:

| Area | What it is |
|------|------------|
| `common/` | dependency-light shared libraries (domain primitives, JSON-Schema subset, files, web scraping, MiniScript, seeding) |
| `taskqueue/` | dependency-free task queue: virtual-thread workers, suspend/resume, retries, cron, optional Postgres store |
| `workflow/` | small workflow engine, embeddable as a library: typed steps, scoped variables, script steps, admin UI |
| `agents/` | the agent runtime: turn loop, tool dispatch, sub-agents, approvals, LLM gateway, vector store, REST and admin UI apps |

`agents/` splits along a core/adapter seam: `-core` modules hold domain and
ports, their siblings the adapters. Each area has a README; read it before
working there. How the areas fit, the seams and the patterns with a name:
`doc/agent/architecture.md`. Decisions that would otherwise be made again:
`doc/decisions/`.

## Hard rules

- Everything that lands in the repository is English: code, comments, javadoc,
  docs, commit messages, PR titles and bodies. Conversation can be German.
- No framework in a `-core` module or an engine; Spring wiring only in
  `springstarter/`, an `-app`, `-rest` or `-ui` module. Controllers are thin.
- One responsibility per class; a name says what the thing is for (no
  `Helper`, `Util`, `Manager`); one constructor per class; Javadoc says why,
  never what or history. The full conventions: `doc/agent/conventions.md`,
  numbered as D1–D9 (build) and I1–I8 (review) in `doc/agent/quality-catalog.md`.
- Surgical changes: touch only what the task needs, match the surrounding
  style, one topic per PR. Something you notice outside the task goes into
  `doc/known-gaps.md`, not into the diff.
- A failing test is fixed in the code, never deleted or disabled. Test sources
  stay untouched by a rename, move or split.
- Before you say "done": `./scripts/verify.sh <module>...` is green for every
  module you touched. It runs what CI runs.
- A user-facing change needs a changelog fragment `changelog.d/<section>/<branch-slug>.md`
  (`added`, `changed`, `deprecated`, `removed`, `fixed`, `security`), never an edit
  of `CHANGELOG.md`; refactorings, tests, docs and build plumbing need none
  (label the PR `no-changelog`). See `changelog.d/README.md`.
- Do **not** add a `Co-Authored-By` trailer to commits.
- Keep `website/docs/` in step with behaviour you change.

## Build and verify

```bash
./scripts/verify.sh <module>...        # the one check: tests, PMD baseline, Jacoco, ArchUnit
mvn -f <module>/pom.xml test           # one module
mvn -f <module>/pom.xml test -Dtest=<Class>#<method>
mvn -f agents/pom.xml clean install    # one area, incl. its parent
mvn clean install -DskipTests          # everything, from the root aggregator
```

Build parent POMs and core modules before what depends on them; after changing
a shared module, rebuild it before its dependents. Some tests skip themselves
without their service (pgvector Postgres on 5433, an LM Studio model): a green
run may mean "skipped", read the `Tests run:` and `Skipped:` counts.

## Branch, run

Run `./after-branch-creation.sh` once per clone: builds install as
`0.x.y-<branch>-SNAPSHOT` and `.claude/launch.json` gets this clone's preview
servers on their own ports. Secrets live in `mc.env` (copy of `mc.env.example`).

```bash
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run   # admin UI, port 9090
mvn -f agents/server/mc-agent-api-app/pom.xml spring-boot:run        # REST + SSE
mvn -f agents/client/mc-agent-cli/pom.xml spring-boot:run            # CLI
```

The admin UI needs `MINDCONNECT_ENCRYPTION_SECRET_KEY` (16, 24 or 32 chars).
