---
title: Overview
sidebar_position: 1
---

# workflow

> A **small, fast workflow engine that embeds into any Java application.** The
> core has no framework lock-in — add it as a plain library and drive it from
> your own code.

`workflow` orchestrates steps with control flow, scoped variables and
**multi-language scripting**. A workflow is a tree of typed steps
(`IfStep`, `ForEachStep`, `CallWorkflowStep`, `CodeStep`, `BlockStep`,
`HttpCallStep`, …) executed against a `WorkflowContext` with scoped variables
and expression resolution (the built-in `mini:` language, plus any scripting
language on the classpath).

![Workflow engine — how it works](/img/workflow/how-it-works.svg)

## Motivation

This engine grew out of frustration with the existing options. Activiti,
Flowable and Camunda are powerful, but **heavy** — they pull in large dependency
trees, assume a database and a process server, and turn "run a few steps" into a
platform decision. For embedding a little orchestration into an application,
that is far too much.

There was no real **lightweight** alternative that was at the same time easy to
use, dependency-free, small, and still powerful enough for real logic. So this
is that: a tiny engine you drop into any Java app.

A second motivation comes from **LLM agents**. Giving an agent a new tool
usually means writing and deploying code. A workflow flips that around: because
a workflow is just data (built in Java, JSON, or a PlantUML diagram), **even a
non-programmer can express specific logic** and register the finished workflow
as a tool for the [agent runtime](/agents/overview) — no code change, no
redeploy.

### Design decisions

These goals shaped the architecture:

- **Dependency-free core.** The engine itself has no framework or database
  dependencies, so it embeds and runs anywhere. The built-in `mini:` language
  means you can even script steps without adding anything.
- **Concerns wrapped *around* the core.** Capabilities like persistence,
  scripting languages, the PlantUML DSL, JSON (de)serialization and the diagram
  UI are **separate, opt-in modules** layered on top — never baked into the
  engine.
- **No technology lock-in.** Because those concerns sit at the edges, you choose
  what to use. Want pause/resume? Add the persistence module. Want Groovy? Add
  that module. Want neither? The bare core still runs your workflows.

## What it is

- **Embeddable** — the core is a plain library. No Spring required, no server to
  run. Instantiate it and call `executeWorkflow(...)`.
- **Typed steps** — control flow (`if`, `for-each`, blocks, jumps, halt),
  variable assignment, sub-workflow calls, and HTTP calls.
- **Scripting** — the built-in `mini:` ([MiniScript](./miniscript.md)) language
  out of the box, plus optional BeanShell, JavaScript, Groovy or Jython modules,
  picked per step.
- **Expressions** — `${...}` substitution and `mini:`/script resolution over
  scoped variables.
- **Persistence** — pause and resume long-running workflow instances.
- **Authoring** — build workflows in Java, from JSON (Jackson), or from
  **PlantUML** diagrams.
- **UI** — a diagram view and a Swing editor (optional add-on modules).

## Self-contained

This area stands on its own — it does not depend on the `agents/` area and
can be used in isolation. (The admin UI modules consume the external
[mc-semantic-ui](https://github.com/mindconnect-ai/mc-semantic-ui) framework.)

## In this section

- **[How it works](./how-it-works.md)** — the engine, steps, context and
  expression model.
- **[Getting started](./getting-started.md)** — run your first workflow with a
  small example.
- **[Step reference](./step-reference.md)** — every built-in step, with JSON and
  Java examples.
- **[PlantUML reference](./puml-reference.md)** — the PlantUML DSL syntax for
  every step and control-flow construct.
- **[MiniScript reference](./miniscript.md)** — the built-in `mini:` expression
  language (no extra dependency).
- **[Variable scope](./variable-scope.md)** — the hierarchical variable store
  and how lookups and writes resolve.
- **[Custom steps](./custom-steps.md)** — add your own step type via the
  factory, without touching the engine.
