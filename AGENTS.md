# AGENTS.md

Mindconnect: a Maven monorepo for building LLM-powered agents. Java 21, Spring
Boot 3.5 in the deployable apps, plain Java in the libraries. Packages are all
under `ai.mindconnect.*`.

## Layout

Four areas, each self-contained and usable without the others; only `common/`
is a shared dependency.

| Area | What it is |
|------|------------|
| `common/` | Dependency-light shared libraries: domain primitives, JSON-Schema subset, file manager, web scraper, path accessor, MiniScript, initial-data seeding |
| `taskqueue/` | Dependency-free task queue: virtual-thread workers, suspend/resume, retries, cron, optional Postgres store |
| `workflow/` | Small workflow engine, embeddable as a library: typed steps, scoped variables, script steps, persistence, admin UI |
| `agents/` | The agent runtime: turn loop, tool dispatch, sub-agents, approvals, LLM gateway, vector store, REST and admin UI apps |

`agents/` splits along a core/adapter seam: `-core` modules hold domain and
ports, their siblings hold the implementations. `core/` are libraries,
`adapter/postgres/` alternative stores, `springstarter/` the Boot starters,
`server/` the deployable apps, `client/` the CLI.

Each area has its own README; read it before working in that area. Deeper
documentation lives in `website/docs/`.

## Build and test

```bash
mvn clean install -DskipTests          # everything, from the root aggregator
mvn -f agents/pom.xml clean install    # one area, incl. its parent
mvn -f <module>/pom.xml test           # one module
mvn -f <module>/pom.xml test -Dtest=<Class>#<method>
```

Build parent POMs and core modules before what depends on them. After changing
a shared module, rebuild it before its dependents.

Some tests skip themselves when their service is absent: pgvector Postgres on
5433, an LM Studio model. A green run may mean "skipped" — read the
`Tests run:` and `Skipped:` counts, do not trust the exit code alone.

## Working on a branch

Run this once after creating or checking out a branch:

```bash
./after-branch-creation.sh
```

It writes the git-ignored `.mvn/maven.config`, so every build in this checkout
installs as `0.x.y-<branch>-SNAPSHOT` and parallel branches never overwrite
each other in the shared `~/.m2`. Re-run it after pulling a version bump.

## Running the apps

```bash
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run   # port 9090
mvn -f agents/server/mc-agent-api-app/pom.xml spring-boot:run        # REST + SSE
mvn -f agents/client/mc-agent-cli/pom.xml spring-boot:run            # CLI
```

The admin UI needs `MINDCONNECT_ENCRYPTION_SECRET_KEY` (16, 24 or 32 chars).

## Conventions

- **Everything that lands in the repository is English**: code, comments,
  javadoc, documentation, commit messages, PR titles and bodies, issue
  replies. This is an open source project.
- Parent POMs own dependency versions and plugin config. Do not declare a
  version in a module POM when the parent can.
- Do **not** add a `Co-Authored-By` trailer to commits.
- **A user-facing change needs a line in `CHANGELOG.md`** under
  `## [Unreleased]`, in the section that fits: a new endpoint, a changed
  behaviour, a fixed bug someone may have been living with. Refactorings,
  tests, docs and build plumbing do not. Write it for a reader deciding
  whether to upgrade. A PR touching shipped Java without touching the
  changelog fails the `changelog` check; label it `no-changelog` when that is
  the right answer.
- Manual regression tests live in `agents/doc/manual-tests/`; update a
  `last-verified` stamp only on a pass.
- Keep `website/docs/` in step with behaviour you change.
