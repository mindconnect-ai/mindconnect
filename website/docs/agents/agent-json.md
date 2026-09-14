---
title: Agent definition JSON
sidebar_position: 12
---

# Agent definition JSON

The format of a file in
[`initial-data/agent-definitions/`](./initial-data.md). One file per agent.

## Example (trimmed)

```json title="agent-definitions/research-lead.json"
{
  "id": "00000002-0000-0000-0000-000000000025",
  "name": "research-lead",
  "description": "Research orchestrator. Breaks a question into sub-topics…",
  "systemPrompt": "You are a research lead. …",
  "welcomeMessage": "Hi! I'm a research lead. Give me a research question…",
  "llmConfigName": "agent-default",
  "maxIterations": 10,
  "status": "ACTIVE",
  "memoryConfig": {
    "kind": "auto_compact",
    "compactAtRatio": 0.8,
    "summaryPlacement": "USER_MESSAGE",
    "toolResultEviction": { "afterTurns": 2, "aboveTokens": 800 }
  },
  "responseReviewers": [],
  "callableAgents": ["web-researcher", "verifier"],
  "tools": [
    {
      "id": "00000003-0000-0000-0000-000000000250",
      "agentDefinitionId": "00000002-0000-0000-0000-000000000025",
      "name": "todo_write",
      "description": "Publishes the plan as a checklist.",
      "enabled": true
    }
  ],
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-01T00:00:00Z"
}
```

## Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Stable identifier. Use a fresh UUID for a new agent. |
| `name` | string | Unique agent name; referenced by `run_agent("<name>", …)`. |
| `description` | string | Short summary shown in lists and to orchestrators. |
| `group` | string? | The rubric the admin list files the agent under — the seeds use `assistants`, `sub-agents`, `utilities` and `reviewer`; absent means `general`. `reviewer` is the one group with a meaning: only agents filed there are offered as `responseReviewers` in the admin UI. |
| `systemPrompt` | string | The agent's role and instructions. Supports template vars like `{{ current_date }}`. |
| `welcomeMessage` | string? | Optional greeting shown when a session starts. |
| `llmConfigName` | string | Which [LLM config](./llm-config-json.md) to use (e.g. `agent-default`). |
| `maxIterations` | int | Max tool-loop rounds per turn. |
| `status` | enum | `DRAFT`, `ACTIVE` (usable) or `DEPRECATED`. |
| `memoryConfig` | object | Working-memory strategy (see below). |
| `skills` | object? | Optional: `{ "mode": "SPECIFIC", "names": ["weekly-report", …] }` — which [skills](./skills.md) the agent may load. `mode` is `ALL` (the default, also when the field is absent: every skill the installation, the user and the project have), `SPECIFIC` (only `names`) or `NONE` (no `skill` tool, nothing about skills in the prompt). |
| `callableAgents` | array | The agents this one may hand work to, by name. Naming any gives it `run_agent`, `run_agents` and `list_agents`; empty or absent, it calls no other agent. |
| `callableByAgents` | boolean? | `false` for agents the runtime calls on its own (title generator, summarizers): they are offered in no roster and `run_agent` refuses them. Absent means `true`. |
| `responseReviewers` | array | The agents the answer passes through before the user sees it, in order (e.g. `answer-relevance-checker`). Empty for none. The admin UI offers the agents in the `reviewer` group. |
| `tools` | array | The tools this agent may call (see below). The delegation tools and `tool_search` are never listed here — the runtime adds them (see the table below). |
| `createdAt` / `updatedAt` | timestamp | Bookkeeping. |

### `memoryConfig`

Controls how the conversation is kept within the model's context window.

| Field | Description |
|-------|-------------|
| `kind` | One of `none`, `windowed` (sliding window), `summarizing_window`, `auto_compact` (summarize when full), `full` (keep everything). |
| `compactAtRatio` | For `auto_compact`: compact when the window reaches this fraction of the budget (e.g. `0.8`). |
| `summaryPlacement` | Where the summary goes — `USER_MESSAGE` (auto-compact default) or `SYSTEM_PROMPT` (summarizing-window default). |
| `toolResultEviction` | Drop bulky tool results: `afterTurns` (age) and `aboveTokens` (size). |

When `memoryConfig` is omitted the runtime uses the **`summarizing_window`
default** — not `auto_compact`.

### `tools[]`

Each entry grants the agent one tool.

| Field | Description |
|-------|-------------|
| `id` | UUID for this tool binding. |
| `agentDefinitionId` | The owning agent's `id`. |
| `name` | Tool name, e.g. `web_search`, `file_read`. `run_agent`, `run_agents`, `list_agents` and `tool_search` are dropped when the file is read. |
| `description` | Shown to the model; can override the default description. |
| `enabled` | `true` to make the tool callable. |
| `overrides` | Optional per-agent config map for the tool, e.g. `{ "baseDir": "/tmp" }`. Which keys a tool accepts is shown in the Admin UI tool catalog. |
| `deferred` | `true` keeps the tool out of the prompt until the agent finds it with `tool_search` — for large tool sets like MCP bundles. An agent gets `tool_search` as soon as one tool is deferred, and it finds only the deferred ones. Default `false`. |
| `needsApproval` | `true` requires a human to approve every call before it runs (the turn waits for approval). Default `false`. |

:::tip Editing in the UI
You don't have to hand-edit JSON — the [Admin UI → Agents](./admin-ui/agents.md)
section creates, edits, copies and configures all of this. The JSON is the seed
format and what you check into git.
:::
