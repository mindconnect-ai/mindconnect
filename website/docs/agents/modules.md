---
title: Modules
sidebar_position: 5
---

# Modules

The agents area is split into small modules along a core/adapter seam: `-core`
modules carry the domain and ports (light, no storage/HTTP), their siblings
carry the implementations.

## `core/` — runtime & gateway

| Module | Purpose |
|--------|---------|
| `mc-agent-runtime-core` | The runtime's core: agent and session domain, the ports around it (chat, tools, token counting, memory strategies, repositories) and the use cases — turn loop, prompt assembly, tool dispatch. Free of storage, HTTP and templating. |
| `mc-agent-runtime` | The adapters: file and in-memory repositories, the local in-process runtime, the Pebble prompt renderer, the tokenizer-backed token counters. |
| `mc-agent-memory-strategies` | The [memory strategies](./memory.md) — keep everything, window, summarizing window, auto-compact. Swap this module to change memory behaviour. |
| `mc-llm-gateway-core` | The [gateway's](./llm-gateway.md) core: chat/streaming types and the ports (`LlmChat`, `LlmGateway`, `LlmConfigRepository`) — light enough to be a compile dependency of anything. |
| `mc-llm-gateway` | The gateway implementation: provider adapters, routing, retry/throttle decorators. |
| `mc-message-repository-core` | Conversations, participants and messages as plain domain types, their ports, and the `ConversationService` use case. |
| `mc-message-repository` | File-backed and in-memory implementations of those ports. |
| `mc-file-store-core` | The file store's ports: `FileStore`, `StoredFile`, the `FileStoreBackend` SPI — the runtime core depends on them. |
| `mc-file-store` | The filesystem backend of the file store. |
| `mc-agent-tool-spi` | What a tool *is*: `Tool`, `ToolFactory`, `MultiToolProvider`, `ToolRegistry`, `ToolEnvironment` + the ServiceLoader registry — see [creating a tool](./creating-a-tool.md). |

## `responses-api/` — the protocol a client talks to an agent with

| Module | Purpose |
|--------|---------|
| `mc-agent-protocol` | The protocol vocabulary: `Response`, `Item`, `Conversation`, events and commands — pure types, zero runtime dependencies. |
| `mc-agent-protocol-mc-runtime` | The protocol surface implemented against the Mindconnect runtime. |
| `mc-agent-protocol-openai` | The same surface implemented against the real OpenAI Responses + Conversations API. |

## `tools/core/` — the built-in tool providers

| Module | Purpose |
|--------|---------|
| `mc-agent-tools` | Lightweight built-ins: filesystem, workspace, bash. |
| `mc-agent-tools-code` | `code_execute` — snippets in disposable docker/podman containers. |
| `mc-agent-tools-document` | PDF/Word/text tools via Tika + PDFBox + POI. |
| `mc-agent-tools-web` | HTTP fetch, web search, HTML-to-Markdown. |
| `mc-agent-tools-web-browser` | Headless-Chromium fetching (Playwright) for JS-rendered sites — separate module because of the ~150 MB Chromium download. |
| `mc-agent-tools-workflow` | Persisted workflows as agent tools — one tool per workflow, parameters from its input schema. |

## `tools/vector/` — the knowledge tools

| Module | Purpose |
|--------|---------|
| `mc-vector-store-tools` | The `knowledge` tool group: `vector_upsert/search/delete_file/ingest_file` over the stores in `vectorstore/` |

## `tools/mcp/` — MCP servers as registered tools

| Module | Purpose |
|--------|---------|
| `mc-mcp-gateway-core` | Ports and types: `McpGateway`, `McpRegistryAdmin`, `McpCatalog`, `McpServerRegistration`, `McpTarget`, `McpServerId`. |
| `mc-mcp-gateway-local` | The in-process gateway: registrations on disk, discovery cache, Docker MCP catalog. |
| `mc-mcp-proxy` | One server over stdio or streamable HTTP, on the official MCP Java SDK. |
| `mc-agent-tools-mcp` | The `MultiToolProvider` that exposes every registered server's tools as agent tools. |

## `vectorstore/` — the knowledge layer

| Module | Purpose |
|--------|---------|
| `mc-vector-store` | Vector-store SPI + built-in file-persisted `memory` backend |
| `mc-vector-store-pgvector` | pgvector backend (one table per store, HNSW index) |

See [vector store & file store](./vector-store.md).

## `adapter/` — the ports on Postgres

| Module | Purpose |
|--------|---------|
| `postgres/mc-llm-gateway-pg` | `LlmConfigRepository` on Postgres. |
| `postgres/mc-message-repository-pg` | `ConversationRepository` and `MessageRepository` on Postgres. |
| `postgres/mc-agent-runtime-pg` | The runtime's seven repository ports on Postgres — definitions, sessions, traces, todo lists, summaries, working memory, workspace files. |
| `postgres/mc-file-store-pg` | `FileStore` on Postgres — uploads as `bytea` rows; also the `postgres` backend of the SPI. |

All of them build on `common/mc-jdbc`, a tiny JDBC helper (JSONB document
tables, typed rows, transactions — no ORM). See [Persistence](./persistence.md#postgres).

## `springstarter/` — Spring Boot starters

| Module | Purpose |
|--------|---------|
| `mc-agent-starter-runtime` | Builds the runtime for a Spring Boot application from the same features the embedded builder installs, configured from the `mindconnect.*` properties, and exports its beans; the persistence starters supply the `Persistence`, the namespace starter the scope. |
| `mc-agent-starter-file` | File persistence, the default: every repository port, the LLM-config store and the file store under `mindconnect.data.base-dir`. |
| `mc-agent-starter-postgres` | `mindconnect.persistence=postgres`: every port from one pooled `DataSource`, tables created on start. |
| `mc-agent-starter-registry` | The registry for a server: the store of configured registries (file or Postgres, seeded from `initial-data/registries/` and `mindconnect.registry.default-source`), the GitHub client, the installers and the `RegistryService`. |
| `mc-agent-starter-extension` | The extensions of an installation: the manifests on the classpath, the start-up audit, a namespace's and a brand's decisions (file or Postgres), the `ExtensionService`. |
| `mc-agent-starter-workflow` | Workflows for a server: the workflow stores in the namespace of the call (file or Postgres), the admin's runs in the request's namespace and for its user, the agent-call and tool-call steps wired to the runtime. |

An app has both on the classpath; the property picks. The runtime's own
Spring wiring (`DefaultAgentRuntimeConfig`) still lives in `mc-agent-runtime`
and is imported by the apps — moving it into a starter of its own is the
next step.

## `features/` — capabilities with data of their own

| Module | Purpose |
|--------|---------|
| `mc-user-memory-core`, `mc-user-memory`, `mc-user-memory-pg` | What agents remember about each user across chats: the entries, `UserMemoryService`, the `memory_read`/`memory_write`/`memory_delete` tools and the memory index in the system prompt (`-core`); the file and in-memory stores and `UserMemoryFeature` (`mc-user-memory`); the Postgres store (`-pg`). |
| `mc-agent-todo-core`, `mc-agent-todo`, `mc-agent-todo-pg` | The working todo list of a session: the list, `TodoListService`, the `todo_read`/`todo_write` tools, the `todos`/`todo_list_md` prompt variables and the open-todo hint after a tool call (`-core`); the file and in-memory stores and `TodoFeature`, which also drops a list with its session (`mc-agent-todo`); the Postgres store (`-pg`). |

## `builder/` & `demo/` — embedding

| Module | Purpose |
|--------|---------|
| `mc-agent-runtime-builder` | Spring-free `AgentRuntimeBuilder` facade for embedding the runtime in any Java program; every capability module (tools, vector stores, file store, workflows) is optional. Given a thread-bound scope (`scope(…)`), the runtime works in the namespace of the current call and its tasks carry their scope; without one, in a single namespace. |
| `mc-agent-runtime-feature-core` | The `CoreFeature` every builder installs: the LLM layer, conversations and messages, agent definitions and sessions. `AgentRuntimeBuilder.of(persistence)` with it alone is the smallest runtime that chats. |
| `mc-agent-runtime-feature-skills`, `-tools`, `-workflows`, `-file-upload`, `-transcription`, `-subagents`, `-taskqueue` | Optional features, one Maven module each, registered as services so `installFromClasspath()` finds them: stored skills, the tool registry over the `mc-agent-tools-*` modules, persisted workflows, files attached to a chat, speech to text, delegation to sub-agents, the configured task queue. See [Embedding the runtime](./embedding.md). |
| `mc-agent-simple-demo` | Runnable embedding examples: a minimal chat, and a chat with an attached file searched through the session's vector store. |

## `server/` — what runs

`app/` holds the Spring Boot applications, `rest/` and `ui/` the libraries they embed, `core/` the
installation around the runtime: users, namespaces, credentials, registries and extensions as plain
Java, with their Postgres adapters under `adapter/`.

| Module | Purpose |
|--------|---------|
| `app/mc-agent-api-app` | Agent server (REST, streaming via SSE) — embeds `mc-agent-api-rest` |
| `app/mc-agent-admin-ui-app` | Admin UI Spring Boot application |
| `rest/mc-agent-api-rest` | The REST controllers and DTOs as a library (no Boot main) |
| `rest/mc-agent-api-responses-rest` | The OpenAI Responses API shape over the runtime |
| `ui/mc-agent-admin-ui-rest` | The [Admin UI](./admin-ui/index.md) itself as an embeddable library: controllers serving UiNode JSON under `/admin/api/**`, pages, components, SPA assets |
| `ui/mc-agent-chat-ui-rest` | The chat screens, embedded by the admin UI |
| `ui/mc-agent-registry-admin-ui-rest` | The *Registry* screen, embedded by the admin UI. |
| `ui/mc-mcp-gateway-admin-ui-rest` | The *MCP Servers* screen, embedded by the admin UI. |
| `core/mc-agent-security` | Login and API-token security shared by both applications. |
| `core/mc-user-core` / `mc-user` | The users of an installation and their API tokens: domain, ports and services, with file and in-memory adapters. |
| `core/mc-namespace-core` / `mc-namespace` | The namespaces of an installation and who belongs to them, with file and in-memory adapters. |
| `core/mc-credentials` | User credentials store: OAuth2 tokens + API keys, encrypted at rest, with auto-refresh and a vendor OAuth handler SPI. |
| `core/mc-agent-registry-core` / `mc-agent-registry` | Importing LLM configs, agents, workflows and packages from a registry: a GitHub project with an index. |
| `core/mc-agent-registry-workflow` | The registry's workflow installer, a module of its own so the registry does not drag in the workflow engine. |
| `core/mc-extension-core` / `mc-extension` | What an extension is: manifest, API routes, menu contributions, content per namespace, and its adapters. |

## `client/`

| Module | Purpose |
|--------|---------|
| `mc-agent-cli` | Terminal REPL client — wires the whole platform in a single JVM ([CLI docs](./cli.md)) |
