---
title: Workspace & collaboration
sidebar_position: 6
---

# Workspace & collaboration

A workspace is a file area an agent can read from and write to with three
tools: `workspace_read`, `workspace_write`, and `workspace_list`. It is how an
agent persists results and builds up knowledge — writing files instead of
stuffing everything into its context.

## Example: the research report

The bundled `research-lead` uses its workspace for the final deliverable: it
fans research out to sub-agents, receives their findings back as tool results,
and then writes the assembled `report.md` into its own session workspace —
where the user can open it from the Admin UI.

Note that sub-agents do **not** share a file area with their caller: each
sub-agent runs in its own session, so its session workspace is a different
directory from the parent's. Work travels between agents as tool-call arguments
and results, not through shared files.

## The three scopes

A workspace is not a single bucket — it has three scopes, each with a different
lifetime and visibility:

| Scope | Lifetime | Visibility | Use for |
|-------|----------|------------|---------|
| **Session** | One conversation | The agent in that session | Scratch space — plans, intermediate findings, the report being assembled. |
| **Agent + user** | Persists across sessions | One agent, one user | An agent's long-term memory of working with a specific user. |
| **User** | Persists across sessions | All agents, one user (**read-only for agents** — `workspace_write` only accepts `session` and `agent`) | A cross-agent user profile any agent can draw on. |

The agent-user and user scopes give agents durable memory that outlives a
single conversation, without putting it into the live context window.

## Why files instead of context

Passing results as files keeps token usage bounded: a sub-agent can produce a
large report, write it to the workspace, and return only a short pointer to it.
The orchestrator — and the user — can open the file when needed, but it never
weighs down the main conversation. This pairs naturally with the per-agent
[memory strategies](./memory.md): memory bounds what the model *remembers*, the
workspace bounds what it has to *carry*.
