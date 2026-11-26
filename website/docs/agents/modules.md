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
| `mc-agent-loop-core` | The agent loop on its own: one turn as rounds of asking the model and advancing tool calls. Storage, the model and tool execution sit behind three ports; nothing but the JDK and SLF4J. |
| `mc-agent-runtime-api` | The runtime's API and nothing else: the services a program opens sessions and submits turns with (`SessionService`, `TurnService`), the ports agents, tools, the model and the session store sit behind, and the hook points capabilities attach to. |
| `mc-agent-runtime-capabilities` | What a runtime has by default, each a plugin of its own: skills, tool search, sub-agents, permissions, [memory](./memory.md), attachments, tool results, traces, guards, reviewers, session titles, the prompt. None knows the engine, so each can be left out or replaced. |
| `mc-agent-management-api` | What a user interface or an operator does beside running turns: agents (`AgentManagement`) and sessions (`SessionManagement`) — list, rename, move, delete, read what was said. Interfaces and values only. |
| `mc-agent-skill`, `mc-agent-trace` | Skills and LLM call traces as domain types with their repositories on files and in memory. |
| `mc-file-store-core` | The file store's ports: `FileStore`, `StoredFile`, the `FileStoreBackend` SPI. |
| `mc-llm-gateway-core` | The [gateway's](./llm-gateway.md) core: chat/streaming types and the ports (`LlmChat`, `LlmGateway`, `LlmConfigRepository`) — light enough to be a compile dependency of anything. |
| `mc-llm-gateway` | The gateway implementation: provider adapters, routing, retry/throttle decorators. |
| `mc-file-store` | The filesystem backend of the file store. |
| `mc-agent-tool-spi` | What a tool *is*: `Tool`, `ToolFactory`, `MultiToolProvider`, `ToolRegistry`, `ToolEnvironment` + the ServiceLoader registry — see [creating a tool](./creating-a-tool.md). |

## `responses-api/` — the protocol a client talks to an agent with

| Module | Purpose |
|--------|---------|
| `mc-agent-protocol` | The protocol vocabulary: `Response`, `Item`, `Conversation`, events and commands — pure types, zero runtime dependencies. |
| `mc-agent-protocol-runtime` | The protocol surface implemented on the runtime: a response is a turn, a session a managed session, a conversation the messages of one. |
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
| `postgres/mc-agent-runtime-engine-pg`, `postgres/mc-agent-management-pg` | The session repository and the agent repository on Postgres — a JSONB document per session and per agent. |
| `postgres/mc-agent-skill-pg`, `postgres/mc-agent-trace-pg`, `postgres/mc-agent-tools-todo-pg`, `postgres/mc-agent-tools-usermemory-pg` | Skills, LLM call traces, todo lists and the users' memory on Postgres. |
| `postgres/mc-agent-runtime-infrastructure-pg` | `PostgresStorePlugin`: every store of an installation from one database, as a plugin for the runtime's builder. |
| `postgres/mc-file-store-pg` | `FileStore` on Postgres — uploads as `bytea` rows; also the `postgres` backend of the SPI. |

All of them build on `common/mc-jdbc`, a tiny JDBC helper (JSONB document
tables, typed rows, transactions — no ORM). See [Persistence](./persistence.md#postgres).

## `springstarter/` — Spring Boot starters

| Module | Purpose |
|--------|---------|
| `mc-agent-starter-runtime-engine` | Builds the one `AgentRuntime` of a Spring Boot application through the SDK's builder, from the `mindconnect.*` properties, and hands its services out as beans; the namespace starter supplies the scope, the application adds `Plugin` beans. See [Embedding](./embedding.md#the-same-runtime-in-spring-boot). |
| `mc-agent-starter-file` | What is installation-wide and not the runtime's on files, the default: users, API tokens, credentials. The runtime's own stores come from the runtime starter (`mindconnect.persistence=file`). |
| `mc-agent-starter-postgres` | `mindconnect.persistence=postgres`: the pooled `DataSource` and the same installation-wide stores on Postgres; the runtime starter builds the runtime's stores on that database. |
| `mc-agent-starter-registry` | The registry for a server: the store of configured registries (file or Postgres, seeded from `initial-data/registries/` and `mindconnect.registry.default-source`), the GitHub client, the installers and the `RegistryService`. |
| `mc-agent-starter-extension` | The extensions of an installation: the manifests on the classpath, the start-up audit, a namespace's and a brand's decisions (file or Postgres), the `ExtensionService`. |
| `mc-agent-starter-workflow` | Workflows for a server: the workflow stores in the namespace of the call (file or Postgres), the admin's runs in the request's namespace and for its user, the agent-call and tool-call steps wired to the runtime. |

An app has both persistence starters on the classpath; the property picks.

## `engine/`, `client/` & `demo/` — the runtime, assembled

| Module | Purpose |
|--------|---------|
| `engine/mc-agent-runtime-engine` | Runs turns: the runtime API's services on the task queue, with the agent loop turning inside each turn task. A turn is a task, each tool call a child task. Installed as `EnginePlugin`; its one foreign dependency is the task queue. |
| `engine/mc-agent-runtime-adapters` | What the runtime API's ports are answered with by default, each a plugin: sessions in a session repository and the message repository (`RepositorySessionStore`), a model through the LLM gateway (`LlmGatewayModelPlugin`), prompts rendered with Pebble (`PebblePromptPlugin`). No engine. |
| `engine/mc-agent-management` | The management API on stores — sessions on the session repository of `mc-agent-runtime-adapters`, agents on a repository of their own — as `ManagementPlugin`; `DirectoryPlugin` for where sessions work; `AgentJson` for an agent as a file. |
| `engine/mc-agent-runtime-infrastructure` | What a runtime that serves an installation is assembled from, as plugins: the stores on files or in memory, the LLM gateway, the tool registry, the file and vector stores, the skill sources, the tokenizer. |
| `client/mc-agent-sdk` | The runtime for a Java program: one dependency and one builder, `AgentRuntime.builder()`, that put the engine and the default capabilities together — see [Embedding](./embedding.md). |
| `client/mc-agent-cli-local` | A coding harness on the command line that runs wholly on this machine, on the SDK alone: no server and no Spring. |
| `demo/mc-agent-simple-demo` | Runnable embedding examples: a minimal chat and agents delegating to agents, on a runtime built from the SDK's builder and the infrastructure plugins (`DemoRuntime`). |
| `demo/mc-extension-demo` | The worked example of an [extension](./admin-ui/extensions.md): tools, a plugin, a page and seeds in one jar. |

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
