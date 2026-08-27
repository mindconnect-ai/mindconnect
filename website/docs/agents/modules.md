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
| `mc-credentials` | User credentials store: OAuth2 tokens + API keys, encrypted at rest, with auto-refresh and a vendor OAuth handler SPI. |

## `core/` — protocol

| Module | Purpose |
|--------|---------|
| `mc-agent-protocol` | The protocol vocabulary: `Response`, `Item`, `Conversation`, events and commands — pure types, zero runtime dependencies. |
| `mc-agent-protocol-mc-runtime` | The protocol surface implemented against the MindConnect runtime. |
| `mc-agent-protocol-openai` | The same surface implemented against the real OpenAI Responses + Conversations API. |

## `core/` — tools

| Module | Purpose |
|--------|---------|
| `mc-agent-tool-spi` | What a tool *is*: `Tool`, `ToolFactory`, `MultiToolProvider`, `ToolRegistry`, `ToolEnvironment` + the ServiceLoader registry — see [creating a tool](./creating-a-tool.md). |
| `mc-agent-tools` | Lightweight built-ins: filesystem, workspace, bash. |
| `mc-agent-tools-code` | `code_execute` — snippets in disposable docker/podman containers. |
| `mc-agent-tools-document` | PDF/Word/text tools via Tika + PDFBox + POI. |
| `mc-agent-tools-web` | HTTP fetch, web search, HTML-to-Markdown. |
| `mc-agent-tools-web-browser` | Headless-Chromium fetching (Playwright) for JS-rendered sites — separate module because of the ~150 MB Chromium download. |
| `mc-agent-tools-workflow` | Persisted workflows as agent tools — one tool per workflow, parameters from its input schema. |
| `mc-agent-tools-gmail` | Gmail tools via an MCP Docker container. |
| `mc-mcp-proxy` | MCP wrapper on the official Java SDK: spawns/talks to MCP servers, provides `tools/list` and `tools/call`. |

## `vectorstore/` — the knowledge layer

| Module | Purpose |
|--------|---------|
| `mc-vector-store` | Vector-store SPI + built-in file-persisted `memory` backend |
| `mc-vector-store-pgvector` | pgvector backend (one table per store, HNSW index) |
| `mc-vector-store-tools` | The `knowledge` tool group: `vector_upsert/search/delete_file/ingest_file` |
| `mc-file-store` | Id-addressed file storage with a filesystem backend |

See [vector store & file store](./vector-store.md).

## `builder/` & `demo/` — embedding

| Module | Purpose |
|--------|---------|
| `mc-agent-runtime-builder` | Spring-free `AgentRuntimeBuilder` facade for embedding the runtime in any Java program; every capability module (tools, vector stores, file store, workflows) is optional. |
| `mc-agent-simple-demo` | Runnable embedding examples: a minimal chat, and a chat with an attached file searched through the session's vector store. |

## `server/` — deployable Spring Boot services

| Module | Purpose |
|--------|---------|
| `mc-agent-api-rest` | The REST controllers and DTOs as a library (no Boot main) |
| `mc-agent-api-app` | Agent server (REST, streaming via SSE) — embeds `mc-agent-api-rest` |
| `mc-agent-admin-ui-rest` | The [Admin UI](./admin-ui/index.md) itself as an embeddable library: controllers serving UiNode JSON under `/admin/api/**`, pages, components, SPA assets |
| `mc-agent-admin-ui-app` | Admin UI Spring Boot application |

## `client/`

| Module | Purpose |
|--------|---------|
| `mc-agent-cli` | Terminal REPL client — wires the whole platform in a single JVM ([CLI docs](./cli.md)) |
