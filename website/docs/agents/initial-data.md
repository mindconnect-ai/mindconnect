---
title: Initial data
sidebar_position: 11
---

# Initial data

Agents, LLM configs, skills and workflows are seeded from files on the
classpath. The Admin UI ships this layout (the CLI the same, without
workflows):

```
src/main/resources/initial-data/
├── agent-definitions/
│   ├── research-lead.json
│   ├── web-researcher.json
│   └── …
├── llm-configs/
│   ├── claude-default.json
│   ├── agent-default.json
│   └── …
├── skills/
│   └── pull-request-review.md
└── workflows/
    └── hello.json
```

One file per record. The filename is just a label — the **`name`** field inside
the JSON is the identity (a workflow's is its file name).

## When and how it's loaded

The Admin UI reads `initial-data/` from **every jar on the classpath** — the
app's own and any module or [extension](./admin-ui/extensions.md) added to
it — and installs it into **every namespace**, not only the start-up one:

- the start-up namespace (`mindconnect.namespace`, default `local`) at start;
- every other namespace the **first time it is used** after a start — the
  first request into it, whoever makes it (an admin or a plain user), or the
  first queued task that runs there. Once per namespace and process; after
  that it costs nothing.

Per record:

| Situation | What happens |
|-----------|--------------|
| The namespace **never had** it | **Installed.** |
| A record with that name is **there** — identical or not | **Left alone.** A differing one is listed as CHANGED on **Install → [Migrations](./admin-ui/migrations.md)**; nothing is overwritten automatically. |
| The namespace **had it once** and it was deleted since | **Not installed again.** Migrations lists it as NEW if you want it back. |
| An [extension's manifest](./admin-ui/extensions.md#content-per-namespace) declares it and the extension is **off** in the namespace | **Not installed** — it is, once the extension is switched on. |

"Had it once" is remembered per namespace, in
`<data.base-dir>/<namespace>/system/installed-seeds.json` with file persistence
and in the table `mc_installed_seed` with Postgres: every record the seeding
installed, and every one it found there already. The first run after an
upgrade records what each namespace has; a bundled record a namespace lacks
at that moment — including one an admin deleted before this was remembered —
is installed once.

A record's identity is its `name` (for a skill, the name in its front matter;
for a workflow, its file name without `.json`). The start-up log has one line
per namespace saying what was installed, and one naming the bundled records
that differ from the stored ones.

So editing a JSON file changes the seed for namespaces that do not have the
record yet; for the others, apply the change on the **Migrations** page (or edit
the record in the UI). A new version of the app or of an extension that ships
a record under a new name brings it into every namespace on its next use.

`initial-data/skills/*.md` are [skills](./skills.md); a stored skill is never
compared with the shipped one — it is prose somebody has since rewritten for
their own house, and the shipped wording has no claim on it. Migrations lists a
shipped skill only when the namespace lacks it.

`initial-data/workflows/` holds the example workflows, installed into the
workflow store (with file persistence `<data.base-dir>/<namespace>/workflows`).

The **CLI** loads into its one namespace on every start: a record that is
missing is imported, a differing one is shown as a diff with
`Overwrite stored version? [y/N]`.

Load failures are logged per file and otherwise swallowed — a malformed JSON
shows up as a missing agent, not as a startup error.

## Adding your own

Drop a new `*.json` into the matching folder, give it a unique `name`, and
restart: every namespace gets it on its next use. Reference a new LLM config from an agent's `llmConfigName`, and a new
agent from an orchestrator via `run_agent("<name>", "…")`.

## Format references

- [Agent definition JSON](./agent-json.md)
- [LLM config JSON](./llm-config-json.md)
- [Skills](./skills.md) — the `SKILL.md` format
