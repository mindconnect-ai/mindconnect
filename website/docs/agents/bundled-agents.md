---
title: Bundled agents
sidebar_position: 9
---

# Bundled agents

The Admin UI and the CLI ship almost the same set of agent definitions
(`src/main/resources/initial-data/agent-definitions/`) — the Admin UI adds one
extra, `summarizer`. They're loaded on first start and you can edit or add to
them.

An agent is *system prompt + LLM config + tools*. Most reference the
[`agent-default`](./llm-configs-reference.md) LLM config (local LM Studio) out of the box.

## Assistants & orchestrators

| Agent | What it does | Key tools |
|-------|--------------|-----------|
| **assistant-with-tools** | General developer assistant — runs commands, reads/writes files, browses documents, sends email, and calls other agents. | `bash`, `file_*`, `read_document`, `run_agent`, `todo_write`, `gmail_*` |
| **planner** | Orchestrator: decomposes a large task into sub-tasks and delegates each to a specialized sub-agent. | `run_agent`, `run_agents`, `todo_*`, `workspace_*` |
| **research-lead** | Research orchestrator: splits a question into sub-topics, dispatches them to `web-researcher` **in parallel**, then verifies and writes a report. | `run_agents`, `run_agent`, `todo_*`, `workspace_*` |

See the [research-lead flow](./research-lead-flow.md) for `research-lead` end to
end.

## Web research sub-agents

| Agent | What it does | Key tools |
|-------|--------------|-----------|
| **web-researcher** | Focused web research; finds pages and delegates reading them to `url-reader`, returns structured findings. Uses `openai-default`. | `web_search`, `run_agent` |
| **url-reader** | Reads **one** web page (static or JS-rendered) and returns only the relevant excerpts. | `web_read`, `web_read_browser` |
| **verifier** | Adversarial check: given a claim and its evidence, tries to **refute** it. | `web_read`, `file_read`, `read_document`, `bash` |

## Code & filesystem sub-agents

| Agent | What it does | Key tools |
|-------|--------------|-----------|
| **explorer** | Read-only sweep of the filesystem/codebase to locate files and naming conventions. | `glob`, `file_list`, `file_read`, `bash` |
| **code-analyst** | Read-only deep analysis: reads code and explains how it works. | `file_read`, `glob`, `file_list`, `bash` |
| **file-finder** | Locates files by name, type or location hint. | `file_list`, `glob` |
| **document-analyst** | Answers questions over long documents (PDF, Word) using outline/grep/page-range tools. | `document_outline`, `grep_document`, `read_document` |

## Creative

| Agent | What it does |
|-------|--------------|
| **poet** | Crafts poems on any topic. |

## Internal helpers

These are used by the runtime itself (summarization, titling, quality checks)
rather than driven directly:

| Agent | What it does |
|-------|--------------|
| **conversation-summarizer** | Summarizes older turns for working-memory compression. |
| **tool-summarizer** | Compresses large tool results after the first LLM turn. |
| **summarizer** | Writes concise Markdown summaries of any text it is given (Admin UI only). |
| **title-generator** | Generates a short conversation title from the first message. |
| **answer-relevance-checker** | Reviews a draft answer for relevance and returns a verdict. |

## Customizing

Edit any JSON file under `initial-data/agent-definitions/` to change a prompt,
swap the `llmConfigName`, or adjust the tool list. In the Admin UI you can do
all of this from the agent screens. Add a new file to register a new agent;
reference it from an orchestrator via `run_agent("<name>", "<task>")`.
