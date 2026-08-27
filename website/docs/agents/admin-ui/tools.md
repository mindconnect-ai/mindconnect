---
title: Tools
sidebar_position: 3
---

# Tools

The **Tools** section is the catalogue of tools that agents can be given. Here
you can:

- **Browse** the available tools — grouped by their `group()` rubric with a
  search box — and read their descriptions.
- **Inspect** a tool's input schema (the parameters it accepts) and its
  **config overrides** schema (the keys an agent binding may set in
  `overrides`).
- **Test** a tool by running it with sample input and seeing the result.

The catalogue itself is read-only.

Tools are assigned to agents in the [Agents](./agents.md) section — open an
agent's detail view and add the tools it's allowed to call (e.g. `web_search`,
`file_read`, `run_agent`).

Tools range from filesystem (`file_read`, `glob`, `bash`), documents
(`read_document`, `grep_document`), web (`web_search`, `web_read`,
`web_read_browser`), orchestration (`run_agent`, `run_agents`, `todo_write`),
workspace (`workspace_read/write/list`), to integrations like Gmail.

Want to add your own? See [Creating a tool](../creating-a-tool.md) under
Concepts for how to build and register a custom tool.
