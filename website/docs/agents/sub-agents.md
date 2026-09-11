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

## Agents a project brings with it

A registry every project shares is the wrong place for a reviewer that knows
*this* codebase's rules. Those agents live beside the code, in
`.mindconnect/agents/` in the session's working directory, one Markdown file
each:

```markdown title=".mindconnect/agents/verifier.md"
---
name: verifier
description: Checks that a change really builds and its tests pass
tools: bash, file_read, grep
model: claude-haiku-default
---
You verify. Run the build and the tests, then say what you ran and what came
back. Never report success you have not seen.
```

Everything above the body is optional: without `name` the file name is the
name, without `model` it runs on the caller's, without `tools` it keeps the
caller's own — while `tools: []` keeps none. The body is the system prompt.
`tools` and `disallowedTools` take either form, a comma list or an indented
block:

```yaml
tools:
  - bash
  - file_read
```

`run_agent` finds them by name,
`list_agents` shows them first, and a project agent shadows a registered one
of the same name for that session.

They are read from the directory each time a call resolves one, so an edit
lands on the next call. A sub-agent already running keeps the definition it
started with.

:::info What a project agent may do
Its tools are **the caller's own, narrowed**: `disallowedTools` takes some
away, `tools` keeps the ones it names, and naming a tool the caller does not
have grants nothing. Each tool keeps the approval the caller's binding gives
it, so a project cannot wave `bash` through, and tool search stays off so it
cannot look further tools up either.

That is what makes opening someone else's repository safe: the file can only
narrow what the agent calling it was already allowed to do. It is not subject
to the caller's `callableAgents` roster, though — that roster curates the
registry, and this agent comes from the same directory whose instructions the
caller is already following. What it calls in turn is: it inherits the
caller's roster along with its tools, so an agent limited to two others cannot
reach a third by way of a file in the repository.
:::

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
