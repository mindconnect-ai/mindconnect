---
title: Persistence
sidebar_position: 8
---

# Persistence

Everything the runtime needs to remember — agent definitions, sessions,
messages, working memory, summaries, todos, LLM-call traces, the workspace —
is stored through a small set of **repository ports**. Two implementations
ship: the default writes plain files to disk, the other keeps everything in
**Postgres**. One setting switches between them; nothing else in the runtime,
the tools or the agent definitions knows the difference.

## Ports and adapters

The runtime follows a hexagonal (ports-and-adapters) design. Each persisted
concept is an interface in `port/out`, and the file-based default is the
matching adapter in `adapter/file`:

| Port (`port/out`) | Stores | File adapter (`adapter/file`) | Postgres adapter (`adapter/pg`) |
|-------------------|--------|-------------------------------|---------------------------------|
| `AgentDefinitionRepository` | Agent definitions | `FileAgentDefinitionRepository` | `PgAgentDefinitionRepository` |
| `AgentSessionRepository` | Sessions (incl. parent/child links) | `FileAgentSessionRepository` | `PgAgentSessionRepository` |
| `MessageRepository` | Conversation messages | `FileMessageRepository` | `PgMessageRepository` |
| `ConversationRepository` | Conversation metadata | `FileConversationRepository` | `PgConversationRepository` |
| `WorkingMemoryRepository` | Per-turn working-memory snapshots | `FileWorkingMemoryRepository` | `PgWorkingMemoryRepository` |
| `ConversationSummaryRepository` | Compaction summaries | `FileConversationSummaryRepository` | `PgConversationSummaryRepository` |
| `TodoListRepository` | Session todo lists | `FileTodoListRepository` | `PgTodoListRepository` |
| `LlmCallTraceRepository` | LLM request/response traces | `FileLlmCallTraceRepository` | `PgLlmCallTraceRepository` |
| `LlmConfigRepository` | LLM configs (credentials encrypted) | `FileLlmConfigRepository` | `PgLlmConfigRepository` |
| `WorkspaceStore` | Workspace files | `FileWorkspaceStore` | `PgWorkspaceStore` |
| `FileStore` | Uploaded files (ports in `mc-file-store-core`) | `FilesystemFileStore` | `PgFileStore` |
| `WorkflowDataRepository` | Workflow definitions ([workflow area](../workflow/overview.md)) | `FileWorkflowDataRepository` | `PgWorkflowDataRepository` |
| `WorkflowInstanceRepository` | Suspended workflow runs | `FileWorkflowInstanceRepository` | `PgWorkflowInstanceRepository` |

A port is a plain interface — for example:

```java
public interface AgentSessionRepository {
    AgentSession save(AgentSession session);
    Optional<AgentSession> findById(UUID id);
    List<AgentSession> findByAgentDefinitionId(UUID agentDefinitionId,
                                               Namespace namespace, String userId);
    // …
}
```

## The default: file persistence

In the Spring apps the file adapters come from the **`mc-agent-starter-file`**
starter (`agents/springstarter/`), all rooted at one directory
(`mindconnect.data.base-dir`, default `data/`). The LLM-config repository is
wrapped in an `EncryptingLlmConfigRepository` when the app has an
`EncryptionHelper`, and LLM call traces live under `conversations/` with a
`mindconnect.agent.trace.max-per-session` retention cap (default 50).

This makes the runtime zero-dependency: it boots and persists with nothing but a
writable folder — no database, no migrations, ideal for local development and
single-node deployments.

Besides the file adapters there is a full **in-memory** family
(`InMemoryAgentSessionRepository`, `InMemoryMessageRepository`, …) used by the
Spring-free `AgentRuntimeBuilder` and in tests.

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
  configs, example workflows — is seeded into the database exactly as it
  would be seeded into `data/`, and skipped when already present.
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
statement. A document in the database is the same JSON the file adapter would
have written, rendered by the application's `ObjectMapper`.

The tables are named `mc_agent_definition`, `mc_agent_session`,
`mc_conversation`, `mc_message`, `mc_working_memory`, `mc_conversation_summary`,
`mc_todo_list`, `mc_llm_call_trace`, `mc_llm_config`, `mc_workspace_file`,
`mc_workflow`, `mc_workflow_instance` and `mc_file`. The small JDBC layer
underneath — `Sql`, `Row`, `DocumentTable` — lives in `common/mc-jdbc`; the
adapters are the `agents/adapter/postgres/*-pg` and
`workflow/mc-workflow-persistence-pg` modules, the Spring wiring is
`mc-agent-starter-postgres`.

Two lists are read without touching a document at all:
`AgentSessionRepository.findHeadersByUser` (the chat sidebar) and
`LlmCallTraceRepository.findHeadersByConversation` return `AgentSessionHeader`
and `LlmCallTraceHeader` — views the aggregates implement themselves — straight
from the columns.

### What stays on disk

The registry of vector-store templates and instances, and the vectors of the
`memory` backend are still file-based in Postgres mode. Vectors move to the
database with the `pgvector` backend (`mindconnect.vector-store.backend=pgvector`),
which has its own connection settings. The registry is the next candidate.

### Embedding without Spring

`AgentRuntimeBuilder.usePostgres(dataSource, dataDir)` wires the same adapters
for a plain-Java host; `dataDir` roots the file-based side channels as before.
Workflows and uploads follow when their modules are on the classpath.

### Moving an installation

There is no automatic migration from `data/` to Postgres. A fresh Postgres
start seeds the bundled defaults; your own agents, sessions and conversations
would need to be re-created or copied by a script through the two adapter
families — both speak the same JSON, so that script is a read-and-save loop.

### Testing against Postgres

The adapter tests run against a real Postgres and skip themselves when none
answers on `localhost:5433`:

```bash
podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie
```

Override with `MC_JDBC_TEST_URL`, `MC_JDBC_TEST_USER`, `MC_JDBC_TEST_PASSWORD`.

## Other databases

Every storage concept is a port, so a third backend means implementing those
interfaces — the Postgres adapters are the template: a few dozen lines each
on top of `mc-jdbc`, with the same `@ConditionalOnProperty` switch. Because
each port is independent you can also migrate incrementally, one repository
at a time.
