---
title: Sub-agents
sidebar_position: 2
---

# How sub-agents work

The model picks a sub-agent through a tool call:

```text
run_agent("web-researcher", "Research Qdrant")
```

The sub-agent runs in its **own session** — own prompt, tools and model — does
its work, and returns the result. Sub-agents can call sub-agents recursively,
up to a depth of **5**; each sub-agent call times out after 2 hours.

![Sub-agent recursion](/img/agents/sub-agent-recursion.svg)

## Why delegate?

- **Decompose** complex tasks into focused sub-tasks.
- **Parallelism** — several sub-agents work at once.
- **Specialization** — each sub-agent has a tailored prompt and toolset.
- **Smaller main context** — the orchestrator only sees results, not every
  intermediate tool call.

## The call as a comic

A travel agent delegating flight search to a specialized sub-agent — the same
shape as `research-lead` calling `web-researcher`:

![Sub-agent call](/img/agents/sub-agent-call-comic.svg)
