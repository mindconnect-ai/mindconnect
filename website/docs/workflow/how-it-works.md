---
title: How it works
sidebar_position: 2
---

# How it works

A workflow is a **tree of typed steps**. You hand that tree to a
`WorkflowExecutorService`, which walks it against a `WorkflowContext` —
resolving variables and expressions as it goes — and returns a
`WorkflowResult`.

```
 Definition            Engine                         Result
 ┌──────────┐   ┌───────────────────────┐   ┌──────────────────┐
 │WorkflowData│ │ WorkflowExecutorService │  │ WorkflowResult   │
 │  (steps)  │─▶│  + WorkflowContext      │─▶│  success/halt/   │
 │           │  │  scoped vars · mini:    │  │  error · result  │
 └──────────┘   └───────────────────────┘   └──────────────────┘
```

## The pieces

- **`WorkflowData`** — the definition: a named workflow holding an ordered list
  of steps. Build it in Java, deserialize it from JSON, or parse it from
  PlantUML.
- **Steps** — each step is a typed node:

  | Step | Does |
  |------|------|
  | `AssignVariablesStep` | Set variables from literals or expressions |
  | `IfStep` | Branch on a condition |
  | `ForEachStep` | Iterate a collection (sequential or parallel) |
  | `BlockStep` | Group steps; produces a result |
  | `CallWorkflowStep` | Invoke another workflow |
  | `CodeStep` | Run a script (BeanShell / JavaScript / Groovy / Jython) |
  | `HttpCallStep` | Make an HTTP request |
  | `JumpToStep`, `HaltStep` | Jump to a step; pause/stop execution |
  | `FormStep` | Halt that carries a semantic-ui form; the filled fields become the resume params (`mc-workflow-step-form`) |

- **`WorkflowContext`** — the execution state: **scoped variables**, the input
  parameters, and step results. Built per run by a `WorkflowContextFactory`.
- **Expressions** — string values support `${variable}` substitution; conditions
  and assignments support scripting prefixes, with the built-in `mini:`
  ([MiniScript](./miniscript.md)) language always available. The
  `DefaultWorkflowContextFactory` wires the resolvers.
- **`WorkflowResult`** — the outcome: `isSuccess()`, `isHalted()`, `isError()`,
  and `getResult()` for the final value.

## Scripting

`CodeStep` runs code in a pluggable language runtime. Each language ships as its
own module (`mc-workflow-code-beanshell`, `-javascript`, `-groovy`, `-jython`)
and registers itself via the SPI described below — add the dependency and the
language is available.

## Auto-registration (SPI)

Modules extend the engine without any wiring code. The mechanism is a Java
`ServiceLoader` SPI built around one interface:

```java
public interface WorkflowConfigurer {
    void configure(WorkflowContextFactory factory);
}
```

A module that wants to contribute — a scripting language, the Jackson
serializer, your own step type — ships:

1. a `WorkflowConfigurer` implementation whose `configure(...)` registers its
   step types (`factory.getStepInstanceFactory().register(...)`) and/or
   expression resolvers, and
2. a service file listing that class:

   ```
   META-INF/services/ai.mindconnect.workflow.execution.WorkflowConfigurer
   ```

To apply every configurer on the classpath automatically, add the
**`mc-workflow-spi-lookup`** module and build the factory with
`SpiWorkflowContextFactory.create()`:

```java
import ai.mindconnect.workflow.spi.SpiWorkflowContextFactory;

// ServiceLoader finds every WorkflowConfigurer on the classpath and
// calls configure() on a fresh DefaultWorkflowContextFactory — no manual calls.
WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
WorkflowExecutorService service = new WorkflowExecutorService(factory);
```

Adding a module to the classpath is all it takes; the SPI does the
`configure(...)` for you. These modules ship a configurer out of the box:
`mc-workflow-code-{javascript,groovy,beanshell,jython}`,
`mc-workflow-jackson` and `mc-workflow-step-form`.

Need extra setup on top of discovery? Take the factory `create()` returns and
modify it before building the executor:

```java
WorkflowContextFactory factory = SpiWorkflowContextFactory.create();
factory.getStepInstanceFactory().register(MyData.class, MyStep::new);
WorkflowExecutorService service = new WorkflowExecutorService(factory);
```

(Without `mc-workflow-spi-lookup` you can still wire modules by hand —
`new JavaScriptWorkflowConfigurer().configure(factory)` — but the SPI is the
recommended path.)

## Authoring options

- **In Java** — construct `WorkflowData` and step objects directly (see
  [Getting started](./getting-started.md)).
- **JSON** — `mc-workflow-jackson` (de)serializes definitions.
- **PlantUML** — `mc-workflow-dsl-puml` parses a PlantUML activity diagram into
  `WorkflowData`, so a diagram *is* the source.

## Persistence

`mc-workflow-persistence` stores a `WorkflowInstanceSnapshot` (via the
`WorkflowInstanceRepository` port; a file-based implementation ships) so a
long-running workflow can pause (`HaltStep`) and later resume via
`executorService.continueWorkflow(instance, params)`.
