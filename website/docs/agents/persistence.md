---
title: Persistence
sidebar_position: 8
---

# Persistence

Everything the runtime needs to remember — agent definitions, sessions,
messages, working memory, summaries, todos, LLM-call traces, the workspace —
is stored through a small set of **repository ports**. The default
implementation writes plain files to disk; the ports exist so that storage can
be swapped without touching the rest of the runtime.

## Ports and adapters

The runtime follows a hexagonal (ports-and-adapters) design. Each persisted
concept is an interface in `port/out`, and the file-based default is the
matching adapter in `adapter/file`:

| Port (`port/out`) | Stores | Default adapter (`adapter/file`) |
|-------------------|--------|----------------------------------|
| `AgentDefinitionRepository` | Agent definitions | `FileAgentDefinitionRepository` |
| `AgentSessionRepository` | Sessions (incl. parent/child links) | `FileAgentSessionRepository` |
| `MessageRepository` | Conversation messages | `FileMessageRepository` |
| `ConversationRepository` | Conversation metadata | `FileConversationRepository` |
| `WorkingMemoryRepository` | Per-turn working-memory snapshots | `FileWorkingMemoryRepository` |
| `ConversationSummaryRepository` | Compaction summaries | `FileConversationSummaryRepository` |
| `TodoListRepository` | Session todo lists | `FileTodoListRepository` |
| `LlmCallTraceRepository` | LLM request/response traces | `FileLlmCallTraceRepository` |
| `LlmConfigRepository` | LLM configs (credentials encrypted) | `FileLlmConfigRepository` |
| `WorkspaceStore` | Workspace files | `FileWorkspaceStore` |

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

In the Spring apps the file adapters are **component-scanned** (they are
`@Component`s, not `@Bean` definitions), all rooted at one directory
(`mindconnect.data.base-dir`, default `data/`). Two exceptions: the LLM-config
repository is wrapped in an `EncryptingLlmConfigRepository` in the Admin UI, and
LLM call traces live under `conversations/` with a
`mindconnect.agent.trace.max-per-session` retention cap (default 50).

This makes the runtime zero-dependency: it boots and persists with nothing but a
writable folder — no database, no migrations, ideal for local development and
single-node deployments.

Besides the file adapters there is a full **in-memory** family
(`InMemoryAgentSessionRepository`, `InMemoryMessageRepository`, …) used by the
Spring-free `AgentRuntimeBuilder` and in tests.

## Swapping in a database

Because every storage concept is a port, moving to a database means providing
your own implementations of those interfaces — for example a
`JpaAgentSessionRepository`. Nothing in the runtime, the tools, or the agent
definitions changes; they only depend on the port, not on how it's backed.

Note that the file adapters are picked up by component scanning, so a
replacement is not a simple "define another bean": either exclude the file
adapter from the scan (or mark your implementation `@Primary`), or wire the
runtime yourself via `AgentRuntimeBuilder`, where every repository is an
explicit builder argument.

:::note Not implemented yet
A database adapter does **not** ship today — the shipped implementations are
file and in-memory. The ports are designed for this swap, but the JPA/JDBC
adapters still need to be written. Treat this page as the extension point, not
a feature you can switch on.
:::

You can also migrate **incrementally**: replace one port at a time (e.g. move
sessions and messages to a database while leaving the workspace on disk), since
each bean is independent.
