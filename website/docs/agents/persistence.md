---
title: Persistence
sidebar_position: 8
---

# Persistence

Everything the runtime needs to remember — agents, sessions, messages, todos,
LLM-call traces — is stored through a small set of **repository ports**. Three
implementations ship: the default writes plain files to disk, one keeps
everything in **Postgres**, one keeps it in memory for the life of the process.
One setting switches between them; nothing else in the runtime, the tools or
the agents knows the difference.

## Ports and adapters

The runtime starter (`mc-agent-starter-runtime-engine`) builds every store of the runtime
by `mindconnect.persistence` — the persistence starters themselves keep only what is
installation-wide: the users, their API tokens and credentials.

The stores of one place come as one **store plugin** for the runtime's builder —
`FileStorePlugin`, `InMemoryStorePlugin`, `PostgresStorePlugin` — which provides every
repository below as a service, each built once, on first use. Whoever assembles a runtime
picks the plugin for the backend it wants; everything else asks the runtime for the
repository (`runtime.service(LlmConfigRepository.class)`). The domain modules with several
repositories keep a **repository factory** per backend the plugins use —
`MessageRepositoryFactory` (messages), `LlmRepositoryFactory` (LLM configs and their prices),
`WorkflowRepositoryFactory`.

The runtime follows a hexagonal (ports-and-adapters) design. Each persisted
concept is an interface, with an adapter per backend:

| Port | Stores | File adapter | Postgres adapter |
|------|--------|--------------|------------------|
| `AgentRepository` | Agents | `FileAgentRepository` | `PgAgentRepository` |
| `SessionRepository` | Sessions (incl. parent/child links, and what is kept with a session: attachments, approvals, the summary) | `FileSessionRepository` | `PgSessionRepository` |
| `SessionMessageRepository` | A session's messages, with `mindconnect.sessions.messages=own` | `FileSessionMessageRepository` (`sessions/<id>/messages/`) | `PgSessionMessageRepository` |
| `MessageRepository` | Conversation messages; a session's messages too while `mindconnect.sessions.messages=conversation` | `FileMessageRepository` | `PgMessageRepository` |
| `ConversationRepository` | Conversation metadata | `FileConversationRepository` | `PgConversationRepository` |
| `TodoListRepository` | Session todo lists | `FileTodoListRepository` | `PgTodoListRepository` |
| `LlmCallTraceRepository` | LLM request/response traces | `FileLlmCallTraceRepository` | `PgLlmCallTraceRepository` |
| `SkillRepository` | Stored [skills](./skills.md) | `FileSkillRepository` | `PgSkillRepository` |
| `UserMemoryRepository` | What agents remember about a user | `FileUserMemoryRepository` | `PgUserMemoryRepository` |
| `LlmConfigRepository` | LLM configs (credentials encrypted) | `FileLlmConfigRepository` | `PgLlmConfigRepository` |
| `LlmPriceRepository` | LLM prices, one per config, model and period | `FileLlmPriceRepository` | `PgLlmPriceRepository` |
| `FileStore` | Uploaded files (ports in `mc-file-store-core`) | `FilesystemFileStore` | `PgFileStore` |
| `WorkflowDataRepository` | Workflow definitions ([workflow area](../workflow/overview.md)) | `FileWorkflowDataRepository` | `PgWorkflowDataRepository` |
| `WorkflowInstanceRepository` | Suspended workflow runs | `FileWorkflowInstanceRepository` | `PgWorkflowInstanceRepository` |

A port is a plain interface — for example:

```java
public interface SessionRepository {
    Session create(Session session);
    Optional<Session> find(SessionId id);
    Optional<Session> update(SessionId id, UnaryOperator<Session> change);
    List<Session> findByUser(UserId user);
    // …
}
```

## The default: file persistence

In the Spring apps the file adapters come from `FileStorePlugin`, which the
runtime starter installs for `mindconnect.persistence=file`, all rooted at one directory
(`mindconnect.data.base-dir`, default `data/`) and, below it, one directory per
namespace — so the data of a default install lives under `data/local/`, and a
namespace `acme` under `data/acme/`. Users, API tokens and the namespaces
themselves are installation-wide and live under `data/system/` (the
**`mc-agent-starter-file`** starter keeps those). The LLM-config repository is
wrapped in an `EncryptingLlmConfigRepository` when an encryption key is set, and LLM call traces live under `conversations/` with a
`mindconnect.agent.trace.max-per-session` retention cap (default 50).

Every adapter — file or Postgres — is built once and asks the current `Scope`
on every call which namespace to work in: a file adapter resolves that
namespace's directory, a Postgres adapter matches its `namespace` column. The
scope is bound per request (from the `/ns/{namespace}/` prefix, the
`X-Mindconnect-Namespace` header, or the namespace the user chose in the Admin
UI) and per queued task; nothing above the repositories names a namespace.
Start-up work — the runtime build, the tool warm-up, the seed loaders — binds
`mindconnect.namespace` (default `local`) explicitly; a thread that binds
nothing and touches a store fails instead of working there quietly. That is
also the namespace every
signed-in user may work in without an invitation.

This makes the runtime zero-dependency: it boots and persists with nothing but a
writable folder — no database, no migrations, ideal for local development and
single-node deployments.

Besides the file adapters there is a full **in-memory** family
(`InMemorySessionRepository`, `InMemoryMessageRepository`, …) behind
`InMemoryStorePlugin` — `mindconnect.persistence=memory` in a Spring app, the
plugin itself in a Spring-free embedding — and in tests. Like the others they
ask the scope on every call and keep a store per namespace. The trace store is
bounded so that a long-lived embedded runtime does not grow with every turn: it
keeps as many LLM call traces per session as the plugin is told (the starter
passes `mindconnect.agent.trace.max-per-session`). Deleting a session takes its
messages, its traces, its todo list and its own directory with it, in every
persistence mode.

## Postgres

:::info Since 0.3.0
Postgres persistence ships from version **0.3.0** on. Earlier releases have
the file and in-memory adapters only.
:::

Set one variable and every port above is served by its Postgres adapter:

```bash
MC_PERSISTENCE=postgres                                   # default: file
MC_POSTGRES_URL=jdbc:postgresql://localhost:5432/mindconnect
MC_POSTGRES_USER=mindconnect
MC_POSTGRES_PASSWORD=…
```

The `start.sh` scripts read these from the git-ignored `mc.env`; as Spring
properties they are `mindconnect.persistence` and `mindconnect.postgres.url` /
`username` / `password` / `pool-size`. The Admin UI, the agent server and the
standalone workflow admin app all honour the same switch. Behind it stand two
Spring Boot starters in `agents/springstarter/` — `mc-agent-starter-file` and
`mc-agent-starter-postgres`; an application has both on the classpath and the
property decides which one configures the repositories.

What happens on start:

- One pooled `DataSource` (HikariCP) is opened and shared by every store.
- Each store creates its tables if they are missing — `CREATE TABLE IF NOT
  EXISTS`, plus `ADD COLUMN IF NOT EXISTS` for columns a later version
  declares. There is no migration tool and nothing to run by hand; a plain
  Postgres without extensions is enough.
- The bundled [initial data](./initial-data.md) — agent definitions, LLM
  configs, skills, example workflows — is seeded into the database exactly as
  it would be seeded into `data/`: into the start-up namespace at start, into
  every other namespace on its first use, and only what the namespace never
  had (`mc_installed_seed` remembers what it had).
- Uploaded files go to the database as well (`mc_file`, content as
  `bytea`) unless `mindconnect.file-store.backend` names another backend —
  keeping records in Postgres and files on a volume is a valid pairing.
- The file adapters step back automatically. `mindconnect.data.base-dir`
  still exists for what has no database form: workflow scratch files,
  vector-store files of the `memory` backend, code-execution mounts.

### How the data is stored

Each domain object is one row: the whole object as a **JSONB document**, with
the few values a query filters, sorts or lists by as ordinary columns beside
it — the id, a namespace, a name, a sequence number, a timestamp. The document
is the truth and the columns are the index; both are written in the same
statement. The namespace column is part of every primary key and every query,
so two processes bound to different namespaces can share one database. A document in the database is the same JSON the file adapter would
have written, rendered by the application's `ObjectMapper`.

The tables are named `mc_managed_agent`, `mc_session`, `mc_session_message` (with
`mc_session_message_seq`, each session's last place),
`mc_conversation`, `mc_message`, `mc_todo_list`, `mc_llm_call_trace`,
`mc_skill`, `mc_user_memory`, `mc_llm_config`, `mc_llm_price`,
`mc_workflow`, `mc_workflow_instance`, `mc_file`, `mc_vector_store_template`,
`mc_vector_store_instance` and `mc_vector_store_member`, plus the embedding
index `mc_embedding` and `mc_embedding_field` when the database has pgvector. The small JDBC layer
underneath — `Sql`, `Row`, `DocumentTable` — lives in `common/mc-jdbc`; the
adapters are the `agents/adapter/postgres/*-pg` and
`workflow/mc-workflow-persistence-pg` modules, the Spring wiring is
`mc-agent-starter-postgres`.

One list is read without touching a document at all:
`LlmCallTraceRepository.findHeadersByConversation` returns
`LlmCallTraceHeader` — a view the aggregate implements itself — straight
from the columns.

### What stays on disk

The vector stores keep nothing on disk in Postgres mode — as long as the
database has the pgvector extension. Their registry of templates and instances
is in `mc_vector_store_template` and `mc_vector_store_instance` (filled once
per namespace from `vector-stores/templates|instances`), and their vectors are
in one pgvector table per store, in the application's own database: `pgvector`
is the default backend there and needs no URL of its own. A plain
`postgres:16-alpine` has no pgvector; then the vectors stay on the `memory`
backend in `<data-dir>/<namespace>/vector-stores/*.jsonl`, with a warning at
start. Install pgvector into the image — for an existing Alpine data directory
an Alpine build, since a Debian image changes the text collation — and the
stores move into the database on first use — see
[Vector stores](./vector-store.md#the-pgvector-backend).

### Embedding without Spring

`new PostgresStorePlugin(sql, dataDir, maxTracesPerSession, files)` — module
`mc-agent-runtime-infrastructure-pg` — brings the same adapters to the builder
of a plain-Java host; `dataDir` roots the file-based side channels as before,
and `files` says whether uploads are kept in the database too.

### Moving an installation

There is no automatic migration from `data/` to Postgres. A fresh Postgres
start seeds the bundled defaults; your own agents, sessions and conversations
would need to be re-created or copied by a script through the two adapter
families — both speak the same JSON, so that script is a read-and-save loop.

Workflows are the exception: the first time a namespace is used on Postgres,
the definitions and suspended runs the file stores kept under
`<data dir>/<namespace>/workflows` are imported, when `mc_workflow` and
`mc_workflow_instance` have nothing for the namespace yet. Once — a row in
`mc_workflow_import` records it, so what is deleted later stays deleted — and
the files are left where they are.

### Testing against Postgres

The adapter tests run against a real Postgres and skip themselves when none
answers on `localhost:5433`:

```bash
podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie
```

Override with `MC_JDBC_TEST_URL`, `MC_JDBC_TEST_USER`, `MC_JDBC_TEST_PASSWORD`.

## Migrations

What a new version needs changed in stored data is a **migration**: a Java
class in `agents/engine/mc-agent-migration` that implements `Migration` — an
id, the version of mindconnect it came with, and `migrate()`. The runner
applies the migrations a namespace has not had yet, ordered by version, and
logs each one with that namespace's data: the table `mc_migration` in Postgres,
`<namespace>/system/migrations/<id>.json` on files. The highest version in the
log is the version the namespace's data is at. A migration that fails is not
logged, stops the ones after it, and runs again on the next start, so a
migration must leave what it finds done already.

The runtime starter runs them in every namespace the first time it is used
after start (all existing namespaces are used at start), before anything else
works there; `mindconnect.migrations.enabled=false` switches that off. Another
module adds one by contributing a `Migration` to the plugin registry.

The first migration, `earlier-runtime` (0.8.5), takes over what the earlier
runtime kept: its agents (`mc_agent_definition`, `system/agents/`) become
managed agents, its sessions (`mc_agent_session`, `sessions/<id>/session.json`)
sessions of the runtime with the same ids, and each conversation the session's
messages, with their ids, places and times, plus its latest summary. The
earlier data stays where it is.

## Other databases

Every storage concept is a port, so a third backend means implementing those
interfaces — the Postgres adapters are the template: a few dozen lines each
on top of `mc-jdbc`, with the same `@ConditionalOnProperty` switch. Because
each port is independent you can also migrate incrementally, one repository
at a time.
