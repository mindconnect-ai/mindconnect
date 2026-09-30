---
title: Project workflows
---

# Project workflows

A workflow that belongs to one codebase — build the release notes, turn a
Word document into Markdown sections — can live beside the code instead of in
the workflow store, the way [project agents](sub-agents.md) and
[skills](skills.md) do. Put a YAML file into `.mindconnect/workflows/` in the
session's working directory:

```
.mindconnect/workflows/release-notes.yaml          ← a single file
.mindconnect/workflows/word-report/workflow.yaml   ← a directory, with files the workflow reads
```

The name is the file's name without `.yaml`/`.yml`, or the directory's. When
the project has at least one, the agent gets the tool `run_workflow`: its
description lists the workflows with their inputs, and the model calls
`run_workflow(workflow, input)`. The file is read on every call, so an edit is
what the next run does. A file that does not load is listed with the reason.

## The format

A step says what it does with its first key; everything the JSON store spells
out (`@class`, `type`, empty fields) is left away.

```yaml title=".mindconnect/workflows/word-report.yaml"
description: Summarises every section of a Word document
input:
  wordFile: string                                  # required
  targetDir: {type: string, default: out, description: Where to write}
result: summary
steps:
  - tool: document_sections
    args: {path: "${wordFile}"}
    as: sectionsJson

  - code: |
      import json
      sections = [s for s in json.loads(sectionsJson)["sections"] if s["content"].strip()]

  - foreach: sections
    item: section
    join: "\n\n"
    as: summary
    steps:
      - agent: Summarizer
        message: |
          Summarise this section in Markdown, no preamble.
          ${section}

  - if: len(sections) > 10
    then:
      - set: {note: "a long document"}
    else:
      - set: {note: "a short one"}
```

| Step | Does | Keys |
|---|---|---|
| `tool: <name>` | calls one of the caller's tools | `args` (mapping; each value resolves on its own, and a value that is exactly `${var}` passes the variable as it is, list or map included), `failOnError` |
| `agent: <name>` | sends a message to an agent the caller may call | `message` |
| `code: <program>` | runs Python or Node in the sandbox | `language` (`python`, default, or `node`); or `code: {file: step.py}` beside the workflow |
| `set: {var: value}` | assigns variables | a string is taken as written, anything else keeps its type |
| `if: <condition>` | runs `then` or `else` | the condition is MiniScript |
| `foreach: <list variable>` | runs `steps` per item | `item` (default `item`), `index`, `parallel`, `join`, `result` |
| `block:` | groups steps | `result` |

Every step takes `name` (default `<type>-<n>`) and `as`, the variable its
result goes to. Inputs are required unless they have a `default` or say
`required: false`; `result` names the variable the workflow returns. An
unknown key is an error, so a typo does not go unnoticed.

## What a project workflow can reach

A workflow from a repository was written by whoever wrote the repository, so
it is held to the agent that runs it:

- **Tools and agents** — only the caller's own, each run with the caller's
  binding (its pinned parameters, alias and container settings), and none that
  binding makes ask for an approval: a step has nobody to ask. `run_workflow`
  is bound by the runtime alone; a row added to an agent by hand is ignored.
- **Code** runs through the caller's `code_execute` — the local podman/docker
  container or the virtual environment — never inside the server. The
  workflow's variables arrive as the program's top-level variables; the ones
  it creates or changes come back, as far as they are JSON, and the one called
  `result` is the step's result. In Node, `var` and plain assignments come
  back, `let` and `const` stay local, and the code runs synchronously.
- **Expressions** are `${var}`, `json: …` and [MiniScript](../workflow/miniscript.md)
  in a restricted mode: methods only on strings, numbers, booleans, lists and
  maps, only their public API, no `getClass`. A `javascript:`, `groovy:` or
  similar expression in a `set` value or an `if` condition is rejected when the
  file is read; elsewhere such text is just text.
- **Files** — a workflow file, and a file a code step names, must really be
  inside the project: a symlink pointing elsewhere does not load.
- **Not available:** calling another workflow, HTTP from the server, halts and
  forms, and agents defined inline. The server's environment (`env`) is not
  handed in.
