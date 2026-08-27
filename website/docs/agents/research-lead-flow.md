---
title: The research-lead flow
sidebar_position: 3
---

# Example: the research-lead flow

A `research-lead` agent decomposes a question, spawns `web-researcher`
sub-agents in parallel, has a `verifier` check the findings, and writes the
final answer to the shared workspace.

![Research-lead flow](/img/agents/research-lead-flow.svg)

## Step by step

1. **Question** — "compare Qdrant, Weaviate and Milvus."
2. **Plan** — `research-lead` breaks it into sub-topics (`todo_write`).
3. **Research in parallel** — `run_agents` spawns one `web-researcher` per
   database; each runs `web_search` in its own session (page reading is
   delegated to `url-reader`, see below).
4. **Verify** — a `verifier` confirms or refutes the findings.
5. **Report** — `workspace_write` produces `report.md`.

## One level deeper: web-researcher → url-reader

Each `web-researcher` can itself delegate: one `url-reader` sub-agent per URL,
so a noisy page is read in isolation and only the relevant excerpts return.

![web-researcher delegates to url-reader](/img/agents/web-researcher-url-reader.svg)
