# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MindConnect (repo `mindconnect-ai/mindconnect`) is a multi-module Maven monorepo for building LLM-powered agents.
It is built with Java 21; the deployable apps use Spring Boot 3.5.x, the core
libraries are plain Java. The repo is organized into four areas plus one shared
parent POM.

**Each area is fully self-contained and works independently** — there is no required
adoption of the whole stack. `workflow` is a small, fast workflow engine that embeds as
a plain library into any Java application. `taskqueue` is a dependency-free task queue.
`agents` is a standalone agent platform. Only `common/` is a shared dependency, and its
modules can be pulled in individually.

The server-driven UI framework (semantic-ui) lives in its own repository —
[mindconnect-ai/mc-semantic-ui](https://github.com/mindconnect-ai/mc-semantic-ui) —
and is consumed here as published Maven artifacts (`semantic-ui.version` in
`mc-java-parent/pom.xml` pins the version).

Each area has its own README — read it first when working in that area:

- [agents/README.md](agents/README.md)
- [workflow/README.md](workflow/README.md)
- [taskqueue/README.md](taskqueue/README.md)
- [common/README.md](common/README.md)

## Repository Layout

The root `pom.xml` is an aggregator that builds, in order: the parent POMs, the shared
`common/` libraries, then `taskqueue/`, `workflow/` and `agents/`.

- **`mc-java-parent/`** — the one repo-wide parent POM (install first; base
  Java 21 config, plugin management, Lombok, Spring Boot BOM, the Maven Central
  `release` profile). Each area's own parent lives inside that area
  (`agents/mc-agents-parent`, `workflow/mc-workflow-parent`) so an area can
  move to its own repo wholesale.

- **`common/`** — shared, dependency-light libraries
  - `mc-common`: domain primitives and shared types
  - `mc-schema`: typed model of the JSON-Schema subset used for tool signatures and workflow params
  - `mc-file-manager`: file storage / upload / download utilities
  - `mc-webscraper`: web scraping and content extraction (jsoup + Playwright backends)
  - `mc-pathaccessor`: navigate / read / write nested object & JSON paths
  - `mc-script-mini`: minimal embeddable script runner (MiniScript)
  - `mc-initial-data`: seed an app from bundled classpath resources on first run

- **`taskqueue/`** — dependency-free task queue (virtual-thread workers,
  suspend/resume, retries, cron scheduling); the agents sub-agent engine runs on it
  - `mc-task-queue`: core — ports + local implementation, plus the channel (observation) package
  - `mc-task-queue-schedule`: cron addon
  - `mc-task-queue-jdbc`: Postgres-backed stores (SKIP LOCKED claims, leases)
  - `mc-taskqueue-demo-app`, `mc-taskqueue-cluster-demo`: runnable demos

- **`workflow/`** — small, fast workflow engine, embeddable as a library into any Java app
  (steps, control flow, scoped variables, MiniScript/SpEL)
  - `mc-workflow`: core engine
  - `mc-workflow-code-{beanshell,javascript,groovy,jython}`: per-language script steps
  - `mc-workflow-jackson`: JSON (de)serialization of definitions
  - `mc-workflow-persistence`: pause / resume workflow instances
  - `mc-workflow-step-form`: a halt step that carries a semantic-ui form
  - `mc-workflow-dsl-puml`: build workflows from PlantUML
  - `mc-workflow-admin-rest`, `mc-workflow-admin-app`: embeddable workflow admin UI + standalone app
  - `mc-workflow-ui-diagram`, `mc-workflow-ui-diagram-app`, `mc-workflow-swing-ui`: UIs
  - `mc-workflow-spi-lookup`, `mc-workflow-test`: pluggable-step lookup, test fixtures

- **`agents/`** — the agentic runtime (the centerpiece), split along a
  core/adapter seam (`-core` modules carry domain + ports, siblings the implementations)
  - `mc-agents-parent/` — the area parent POM
  - `core/` — libraries (no runnable apps)
    - `mc-agent-runtime-core` / `mc-agent-runtime`: execution engine (turn loop, tool
      dispatch, sub-agent calls, approvals) / its adapters (file & in-memory repos,
      Pebble prompt renderer, tokenizer)
    - `mc-agent-memory-strategies`: working-memory strategies (window, summarizing window, auto-compact)
    - `mc-llm-gateway-core` / `mc-llm-gateway`: LLM abstraction ports / provider adapters + routing
    - `mc-message-repository-core` / `mc-message-repository`: conversation & message storage
    - `mc-agent-protocol` (+ `-openai`, `-mc-runtime`): protocol vocabulary and backend adapters
    - `mc-agent-tool-spi`: what a tool is — `Tool`, `ToolFactory`, `MultiToolProvider`, registry
    - `mc-agent-tools*`: built-in tool providers (filesystem/workspace/bash, code, document, web, web-browser, workflow, gmail)
    - `mc-credentials`: credential storage for tools & providers
    - `mc-mcp-proxy`: proxy for Model Context Protocol servers
  - `vectorstore/` — the knowledge layer: `mc-vector-store` (SPI + memory backend),
    `mc-vector-store-pgvector`, `mc-vector-store-tools`, `mc-file-store`
  - `builder/mc-agent-runtime-builder` + `demo/mc-agent-simple-demo` — Spring-free
    embedding facade and runnable examples
  - `server/` — deployable Spring Boot services
    - `mc-agent-api-rest` / `mc-agent-api-app`: REST API library / agent server (streaming via SSE)
    - `mc-agent-admin-ui-rest` / `mc-agent-admin-ui-app`: admin UI library / app (port 9090)
  - `client/mc-agent-cli`: terminal REPL client

## Architecture Notes

### Agentic runtime

The agents area implements **meta-assistants**: an agent is *system prompt + model + tools*
with its own session, memory and message history. A main agent can call specialized
sub-agents via a tool (e.g. `run_agent("web-researcher", "…")`); each sub-agent runs in its
own session and can call sub-agents recursively. Turns and tool calls run as tasks on the
task queue; tools can require human approval per agent binding. Architecture diagrams live
in [`agents/doc/`](agents/doc/).

### Semantic UI (external)

A Spring Boot controller returns a typed `UiPage` tree of `UiNode`s; the same tree renders
as SSR HTML, as a live SPA, or inside the visual editor. The framework lives in
[mindconnect-ai/mc-semantic-ui](https://github.com/mindconnect-ai/mc-semantic-ui) with its
own docs site; the admin UIs here depend on `mc-semantic-ui-core` and the `ext-*` addons.

### Workflow engine

Workflows are trees of typed steps (`IfStep`, `ForEachStep`, `CallWorkflowStep`, `CodeStep`,
…) executed against a `WorkflowContext` with scoped variables and expression resolution
(built-in MiniScript, plus BeanShell, JavaScript (Nashorn), Groovy or Jython as addons).
Persisted workflows can be exposed as agent tools (`mc-agent-tools-workflow`).

### Docs site

`website/` is a Docusaurus site deployed via GitHub Pages from this repo
(`.github/workflows/docs.yml`). Keep it in sync with code changes.

## Build Commands

```bash
# Build everything from the root aggregator
mvn clean install -DskipTests

# Build a single area (each area builds standalone, incl. its parent)
mvn -f agents/pom.xml    clean install -DskipTests
mvn -f workflow/pom.xml  clean install -DskipTests
mvn -f taskqueue/pom.xml clean install -DskipTests

# Build a single module
mvn -f <module-path>/pom.xml clean install

# If you change a core/shared module, rebuild it before dependent modules

# Release to Maven Central (CI: .github/workflows/release.yml; needs the
# `central` server credentials and a GPG key — see mc-java-parent/pom.xml)
mvn -Prelease deploy
```

## Running Tests

```bash
# All tests for a module
mvn -f <module-path>/pom.xml test

# A single test class / method
mvn -f <module-path>/pom.xml test -Dtest=<TestClassName>
mvn -f <module-path>/pom.xml test -Dtest=<TestClassName>#<methodName>
```

Some integration tests skip themselves when their backing service is absent —
e.g. `PgVectorStoreTest` needs a pgvector Postgres
(`podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie`)
and the LM-Studio suites need a local model. A green run may mean "skipped";
check the `Tests run:`/`Skipped:` counts.

## Running Applications

```bash
# Admin UI (port 9090; needs MINDCONNECT_ENCRYPTION_SECRET_KEY, 16/24/32 chars)
mvn -f agents/server/mc-agent-admin-ui-app/pom.xml spring-boot:run

# Agent server (REST + SSE)
mvn -f agents/server/mc-agent-api-app/pom.xml spring-boot:run

# Agent CLI (local mode by default; remote via mindconnect.remote.url)
mvn -f agents/client/mc-agent-cli/pom.xml spring-boot:run
```

## Key Technologies

- **Java 21** (virtual threads, modern language features)
- **Spring Boot 3.5.x** for the deployable apps (Spring Web, Security; streaming via SSE) —
  the core libraries are framework-free
- **Lombok** for boilerplate reduction
- **Jackson** for JSON/YAML and polymorphic UiNode (de)serialization
- **Multi-language scripting**: MiniScript (built-in), BeanShell, Nashorn (JavaScript), Groovy, Jython (workflows)
- **Postgres** (pgvector vector-store backend, task-queue JDBC store); file-based persistence by default
- **MCP** (Model Context Protocol) support via `mc-mcp-proxy`

## Conventions

- All Java packages use the `ai.mindconnect.*` root
  (e.g. `ai.mindconnect.agent.*`, `ai.mindconnect.workflow.*`, `ai.mindconnect.taskqueue.*`).
- Parent POMs (`mc-java-parent/`, `agents/mc-agents-parent`, `workflow/mc-workflow-parent`)
  own dependency versions and plugin config — prefer them over per-module version declarations.
- Build parent POMs and shared/core modules before the modules that depend on them.
- Manual regression tests live in `agents/manual-tests/` — human/LLM-executable
  scripts with per-step expectations; update `last-verified` stamps only on PASS.
- **Everything written is in English** — code comments, javadoc, documentation,
  commit messages, PR titles and descriptions, issue replies. This is an open
  source project; a German commit message or PR body shuts out most of the
  people who might read it. Conversation with the maintainer can be in German,
  but nothing that lands in the repository or on GitHub.
- Git commits: do **not** add a `Co-Authored-By` trailer.
- **Before committing a user-facing change, add a line to `CHANGELOG.md`**
  under `## [Unreleased]`. User-facing means someone using the libraries or
  running the apps would want to know: a new endpoint, a changed behaviour, a
  fixed bug whose symptom they may have been living with. Refactorings, tests,
  docs and build plumbing do not need one — write the entry for a reader
  deciding whether to upgrade, not for the commit log, which the releases page
  already has. A PR that touches shipped Java without touching the changelog
  fails the `changelog` check; label it `no-changelog` when that is the right
  answer. The release workflow renames `[Unreleased]` to the version being cut
  and opens a fresh one, so nothing is moved by hand.
