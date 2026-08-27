---
title: Overview
sidebar_position: 2
---

# Agents

A runtime for **meta-assistants**: an agent that can call other agents. Each
agent is *system prompt + model + tools* with its own session, memory and
message history. A main agent decomposes a task and hands sub-tasks to
specialized sub-agents — for parallelism, specialization, and a smaller main
context.

This area is a self-contained platform — it builds and runs on its own and
does not require the workflow or semantic-ui areas.

![Agent runtime internals](/img/agents/agent-runtime-internals.svg)

## See it in action

A `research-lead` agent planning a task, spawning `web-researcher` sub-agents in
parallel, having a `verifier` check their findings, and writing the result to
the workspace. The diagram shows the flow; the recording below shows the same
flow running live in the Admin UI.

![Research-lead flow](/img/agents/research-lead-flow.svg)

![The agent runtime running the research-lead flow](/img/agents/sub-agents-flow.webp)

## What's in this section

- **[Getting started](./getting-started.md)** — build, set the environment, run
  the Admin UI.
- **[Admin UI](./admin-ui/index.md)** — the visual console and its sections.
- **[CLI client](./cli.md)** — the terminal chat client and its commands.
- **[Sub-agents](./sub-agents.md)** — how an agent calls another agent.
- **[The research-lead flow](./research-lead-flow.md)** — a worked end-to-end
  example with parallel sub-agents.
- **[Memory](./memory.md)** — per-agent memory strategies (auto-compression,
  summarizing window, …).
- **[Workspace & collaboration](./workspace.md)** — the shared file area, its
  three scopes, and how agents exchange work.
- **[Built-in tools](./built-in-tools.md)** — every tool that ships, and what it
  needs.
- **[Creating a tool](./creating-a-tool.md)** — add your own tool via the SPI.
- **[LLM gateway](./llm-gateway.md)** — provider routing, retry and throttling.
- **[Prompt renderer](./prompt-renderer.md)** — template variables in system
  prompts.
- **[Persistence](./persistence.md)** — the storage ports and the file adapters.
- **[How it compares](./how-it-compares.md)** — positioning vs. code-first
  frameworks.

**Configuration & reference**

- **[Environment variables](./environment-variables.md)** — every variable you
  can set.
- **[Initial data](./initial-data.md)** — how agents and configs are seeded.
- **[Agent definition JSON](./agent-json.md)** and
  **[LLM config JSON](./llm-config-json.md)** — the file formats.
- **[LLM configuration reference](./llm-configs-reference.md)** — bundled
  configs and providers.
- **[Bundled agents](./bundled-agents.md)** — the agents that ship out of the box.
- **[Modules](./modules.md)** — the libraries, servers and clients.
