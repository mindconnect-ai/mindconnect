---
title: Built-in tools
sidebar_position: 9
---

# Built-in tools

Tools are the capabilities you grant to an agent. Most ship in a
`mc-agent-tools-*` module and register via the `ToolFactory` SPI — add the
module to the runtime and the tool is available to assign. Some live in
`mc-agent-runtime-core` itself (the todo tools, `tool_search`, and the inline
`run_agent`/`run_agents`), and dynamic bundles like Gmail or workflow tools use
the second SPI, `MultiToolProvider`. This page lists the built-in tools, what
they do, and any configuration or environment keys they need.

Most tools need **no** configuration. The ones that do are called out in the
**Needs** column; environment variables are documented on the
[environment variables](./environment-variables.md) page.

## Core tools (`mc-agent-tools`)

Always available; no API keys required.

| Tool | Description | Needs |
|------|-------------|-------|
| `get_current_datetime` | Returns the current date and time in ISO-8601 with timezone. | — |
| `list_agents` | Lists the agents in the current namespace with their names and descriptions. | — |
| `bash` | Executes a bash command in the configured working directory. | Working dir (config) |
| `file_read` | Reads a plain-text file (path relative to the base directory). | Base dir (config) |
| `file_write` | Writes a file, creating parent directories as needed. | Base dir (config) |
| `file_list` | Lists files and directories under a path. | Base dir (config) |
| `glob` | Finds files by name pattern, newest first. | Base dir (config) |
| `fetch_tool_result` | Reloads the full content of a tool result evicted from the live context. | — |

## Orchestration tools (`mc-agent-runtime-core`)

| Tool | Description | Needs |
|------|-------------|-------|
| `run_agent` | Delegates a task to another agent by name — see [sub-agents](./sub-agents.md). | — |
| `run_agents` | Fans several sub-agent calls out in parallel. | — |
| `tool_search` | Lets the agent find and activate its *deferred* tools on demand. | The agent's `toolSearch` config |

The file tools resolve paths against a **base directory**
(`mindconnect.tools.base-dir`). It defaults to the **user home** — in the Admin
UI too (its `mindconnect.data.base-dir: ./data` is a different property that
only controls where *stored data* lives, not the file tools).

## Workspace tools (`mc-agent-tools`)

The shared file area agents use to exchange work (see
[workspace & collaboration](./workspace.md)). No keys required.

| Tool | Description |
|------|-------------|
| `workspace_write` | Writes (or overwrites) a file in a workspace scope. |
| `workspace_read` | Reads a file from a workspace scope. |
| `workspace_list` | Lists files available in a workspace scope. |

## Web tools (`mc-agent-tools-web`)

| Tool | Description | Needs |
|------|-------------|-------|
| `web_search` | Searches the web — the agent's first stop for web information. | **`TAVILY_API_KEY`** — the tool is disabled if it's not set |
| `web_read` | Fetches and reads the full content of a specific URL as text (HTML converted to text). | — |

`web_search` uses [Tavily](https://tavily.com). Provide the key via the
`TAVILY_API_KEY` environment variable; without it the factory reports the tool
as unavailable.

## Browser tool (`mc-agent-tools-web-browser`)

| Tool | Description | Needs |
|------|-------------|-------|
| `web_read_browser` | Fetches a URL via a **headless browser** (JavaScript executed) and returns the rendered page — for JS-heavy sites `web_read` can't handle. | Playwright Chromium (downloaded on first use) |

## Document tools (`mc-agent-tools-document`)

For long PDFs and Word `.docx` files. No keys required (base dir config like the
file tools).

| Tool | Description |
|------|-------------|
| `document_outline` | Returns the structural outline of a long document (PDF / `.docx`). |
| `document_sections` | Lists a document's sections. |
| `read_document` | Reads a specific page range from a long document. |
| `read_document_section` | Reads one section by reference. |
| `grep_document` | Searches inside a single long document for a regex pattern. |
| `document_file_read` | Reads a document file from the base directory. |
| `document_write` | Writes a document file. |

## Code execution (`mc-agent-tools-code`)

| Tool | Description | Needs |
|------|-------------|-------|
| `code_execute` | Runs code in a disposable **container sandbox** (docker/podman). | A container runtime; limits via `mindconnect.code-exec.*` (`runtime`, `network`, `languages`, `memory`, `cpus`, `timeout-seconds`, `idle-seconds`) |

## Knowledge tools (`mc-vector-store-tools`)

Semantic search over vector stores; embedding happens inside the tool. Needs an
`EMBEDDING`-type [LLM config](./llm-config-json.md) (default name `embeddings`,
via `mindconnect.vector-store.embedding-config`). See
[vector store & file store](./vector-store.md) for the whole knowledge layer.

| Tool | Description |
|------|-------------|
| `vector_upsert` | Adds/updates chunks in a vector store. |
| `vector_search` | Semantic similarity search. |
| `vector_ingest_file` | Chunks and ingests a whole file. |
| `vector_delete_file` | Removes a file's chunks. |

## Workflow tools (`mc-agent-tools-workflow`)

Every persisted workflow is exposed as its own tool, `workflow_<id>`, with
parameters generated from the workflow's input schema — the bridge between the
agents and the [workflow engine](/workflow/overview).

## Todo tools (`mc-agent-runtime-core`)

A session-scoped planning list the LLM maintains while it works. No keys
required.

| Tool | Description |
|------|-------------|
| `todo_write` | Creates / updates the session todo list. |
| `todo_read` | Reads the current session todo list. |

## Gmail tools (`mc-agent-tools-gmail`)

Gmail is exposed through an **MCP** server (`mcp/gmail`, run via Docker). Each
Gmail sub-tool appears as `gmail_<name>` (e.g. `gmail_search_emails`,
`gmail_read_email`).

| Needs | Notes |
|-------|-------|
| Docker | Runs the `mcp/gmail` image (override with `mc.tools.gmail.docker-image`). |
| OAuth credentials | `gcp-oauth.keys.json` + `credentials.json` in `~/.gmail-mcp` (override with `mc.tools.gmail.credentials-dir`). |

## Assigning tools to an agent

Which tools an agent can use is part of its definition — manage them in the
[Admin UI → Tools](./admin-ui/tools.md) and per-agent in the
[agent detail view](./admin-ui/agents.md). To add a tool of your own, see
[creating a tool](./creating-a-tool.md).
