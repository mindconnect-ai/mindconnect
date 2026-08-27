---
title: Initial data
sidebar_position: 11
---

# Initial data

Agents and LLM configs are seeded from JSON files on the classpath. Both the
Admin UI and the CLI ship the same layout:

```
src/main/resources/initial-data/
├── agent-definitions/
│   ├── research-lead.json
│   ├── web-researcher.json
│   └── …
└── llm-configs/
    ├── claude-default.json
    ├── agent-default.json
    └── …
```

One file per record. The filename is just a label — the **`name`** field inside
the JSON is the identity.

## When and how it's loaded

On startup an `InitialDataLoader` scans `classpath:initial-data/` and applies
each file:

| Situation | What happens |
|-----------|--------------|
| No record with that `name` exists yet | **Imported** (new record). |
| Stored record is **identical** to the file | **Silently skipped**. |
| Stored record **differs** from the file | In the **CLI**: you're shown a diff and asked `Overwrite stored version? [y/N]`. In the **Admin UI**: skipped with a log message — nothing is overwritten on startup. |

So editing a JSON file changes the seed for *fresh* installs immediately; for an
existing install you confirm the overwrite in the CLI, while in the Admin UI you
apply pending changes via the **Migrations** page (or edit the record in the UI).

The Admin UI ships a third seed folder, `initial-data/workflows/`, installed by
a separate mechanism (`InitialWorkflowLoader`, a plain file copy into
`mindconnect.workflow-admin.dir`, default `data/workflows`) that never
overwrites existing files.

Load failures are logged per file and otherwise swallowed — a malformed JSON
shows up as a missing agent, not as a startup error.

## Adding your own

Drop a new `*.json` into the matching folder, give it a unique `name`, and
restart. Reference a new LLM config from an agent's `llmConfigName`, and a new
agent from an orchestrator via `run_agent("<name>", "…")`.

## Format references

- [Agent definition JSON](./agent-json.md)
- [LLM config JSON](./llm-config-json.md)
