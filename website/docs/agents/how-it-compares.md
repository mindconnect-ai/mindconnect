---
title: vs LangChain4j & Spring AI
sidebar_label: vs LangChain4j & Spring AI
sidebar_position: 3
---

# Compared to LangChain4j & Spring AI

LangChain4j and Spring AI are excellent **libraries** for calling LLMs from
Java. The Mindconnect agent runtime is a different kind of thing: a
**configuration-driven platform**. The core difference is where the work lives.

> With LangChain4j or Spring AI you **write an application** that talks to an
> LLM. With the Mindconnect agent runtime you **configure an agent** — and a
> running server already knows how to chat, stream, call tools, orchestrate
> sub-agents, and manage memory.

## The core difference: configure, don't code

A full agent here is a piece of data — its prompt, model, memory strategy,
tools and reviewers all live in a single
[agent definition](./agent-json.md); grant it `run_agent` and it delegates to
any other agent it discovers via `list_agents`. You create it by editing JSON in
`initial-data/`, or entirely through the [Admin UI](./admin-ui/agents.md) form —
**no Java, no rebuild, no redeploy**.

```json
{
  "name": "research-lead",
  "systemPrompt": "You are a research lead. …",
  "llmConfigName": "agent-default",
  "memoryConfig": { "kind": "auto_compact", "compactAtRatio": 0.8 },
  "tools": [ { "name": "run_agents" }, { "name": "workspace_write" } ]
}
```

Switching the model from Claude to GPT to a local model is a config change.
Adding a tool is a line in the `tools` array. Building a multi-agent system is
giving one agent the `run_agent` tool and pointing it at others. None of this
is code.

With a library, each of those is something you wire up and compile yourself.

## Side by side

| | **LangChain4j** | **Spring AI** | **Mindconnect agent runtime** |
|---|---|---|---|
| What it is | Java LLM library | Spring LLM abstraction | Configuration-driven agent platform |
| You ship | Your own app code | Your own app code | Configuration (JSON or UI) |
| Define an agent | Write Java classes | Write Java/Spring beans | A JSON file or a UI form |
| Add a tool | Annotate a Java method, register it | Define a `@Tool` bean | Add it to the agent's `tools` list |
| Swap the model | Change code, recompile | Change config/bean | Change `llmConfigName` |
| Multi-agent | Build the orchestration yourself | Build it yourself | Give an agent `run_agent`; sub-sessions are built in |
| Memory / compaction | Hand-roll or use a module | Hand-roll or use a module | Pick a strategy in `memoryConfig` |
| Server, streaming, UI | You build it | You build it | REST + SSE streaming + Admin UI included |
| Runtime changes | Redeploy | Redeploy | Edit config, no redeploy |

## When to use which

- **Reach for LangChain4j or Spring AI** when you're embedding LLM calls inside
  a larger Java application and want full programmatic control over every step —
  they are libraries, and that is their strength.
- **Reach for the Mindconnect agent runtime** when you want to **stand up and
  operate agents** — define them, give them tools, wire them into multi-agent
  systems, and change all of that without touching code or redeploying.

They are not mutually exclusive: the runtime's
[LLM gateway](./modules.md) sits at the same layer a library would, and a
custom [tool](./creating-a-tool.md) is the escape hatch when you *do* need to
write Java. The point is that for the common case — prompt, model, tools,
memory, orchestration — you shouldn't have to.
