---
title: Agents
sidebar_position: 1
---

# Agents

The **Agents** section manages agent definitions. An agent is *system prompt +
LLM config + tools*.

## Agent list

The list shows every agent, with a search box, pagination and the agent's LLM
config as a badge. From here you can:

- **New Agent** — create a new agent definition.
- **Chat** — start a session with the agent in one click.
- **Copy** — duplicate an existing agent as a starting point for a variant.
- **Delete** — remove an agent.

Click an agent to open its **detail** view — three tabs: **Details** (with Edit
and Delete), **Tools (n)** and **Sessions**.

## Creating & editing an agent

The agent form lets you set:

- **Name**, **Namespace** and description.
- **System prompt** — the agent's role and instructions — and an optional
  **Welcome Message**.
- **LLM Config** — which model it uses, picked from your
  [LLM Configs](./llm-configs.md) (e.g. `agent-default`, `claude-default`).
- **Max Iterations** — the tool-loop budget per turn.
- **Tool Search** — enable `tool_search` and pick the groups it may search.
- **Response Reviewers** — reviewer agents that check answers.

Tools are **not** part of this form — they're managed on the detail view's
Tools tab. Editing is the same form in "edit" mode; **Copy** pre-fills it from
an existing agent.

## Configuring tools

On the **Tools** tab you manage the agent's tool list — add the tools it's
allowed to call (e.g. `web_search`, `file_read`, `run_agent`) and remove ones
it shouldn't have. Each row shows the binding's **Overrides**, **Enabled**,
**Deferred** and **Approval** flags with View / Edit / Delete actions, and a
**test dialog** lets you run a tool with concrete arguments right there. The
tool catalogue itself lives in the [Tools](./tools.md) section.

## Sessions

The **Sessions** tab lists the agent's conversations (title, status, user,
started/completed, sortable). Here you can:

- **New Session** — start a fresh conversation with this agent.
- **Open** — continue an existing session where you left off.
- **Delete** — remove a session.

Opening or starting a session takes you into the chat view, where you see
streamed responses, tool calls and sub-agent runs. The chat view also offers:

- **file attachments** on a turn, and a **Stop** button for a running stream
  (streams survive a page reload — you can re-attach);
- **tool-call approvals** — tools flagged `needsApproval` pause the turn with
  an approve/deny card;
- **Regenerate** a turn, **delete from a message onward**, and clear the chat;
- inspectors for **working memory** (with a manual Compress action),
  **traces**, **todos** and the **workspace** (with file download);
- a **Parent Session** link when you're inside a sub-agent's session.

## See also

- [Bundled agents](../bundled-agents.md) — the agents that ship by default.
- [Sub-agents](../sub-agents.md) — how one agent calls another.
