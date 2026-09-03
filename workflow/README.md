<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="../.github/assets/logo-dark.svg">
    <img alt="Mindconnect" src="../.github/assets/logo-light.svg" width="160">
  </picture>
</p>

<h1 align="center">workflow</h1>

A **small, fast workflow engine that embeds into any Java application** —
the core has no framework lock-in, so you can add it as a plain library
and drive it from your own code. It orchestrates steps with control flow,
variable scopes and **multi-language scripting**. Workflows are defined as
trees of typed steps (`IfStep`, `ForEachStep`, `CallWorkflowStep`,
`CodeStep`, `BlockStep`, …) and executed against a `WorkflowContext` with
scoped variables and Spring-Expression-Language resolution.

This area stands on its own — it does not depend on `agents/` or
`semantic-ui/` and can be used in isolation.

![Workflow engine — how it works](doc/how-it-works.svg)

## Highlights

- **Control flow** — if / for-each / blocks / jumps / halt / sub-workflow calls
- **Scripting** — run step code in BeanShell, JavaScript, Groovy or Jython
- **Expressions** — SpEL-based variable resolution and string substitution
- **Persistence** — pause and resume long-running workflows
- **DSL** — author workflows from PlantUML diagrams
- **UI** — a diagram view and a Swing editor

## Modules

| Module | Purpose |
|--------|---------|
| `mc-workflow` | Core engine: steps, execution, variable scopes, expression resolution |
| `mc-workflow-code-beanshell` | BeanShell script step support |
| `mc-workflow-code-javascript` | JavaScript (Nashorn) script step support |
| `mc-workflow-code-groovy` | Groovy script step support |
| `mc-workflow-code-jython` | Python (Jython) script step support |
| `mc-workflow-jackson` | JSON (de)serialization of workflow definitions |
| `mc-workflow-persistence` | Persist & resume workflow instances |
| `mc-workflow-persistence-pg` | The same two repositories on Postgres (JSONB via `mc-jdbc`); `mindconnect.persistence=postgres` wires them in |
| `mc-workflow-dsl-puml` | Build workflows from PlantUML diagrams |
| `mc-workflow-spi-lookup` | Service-provider lookup for pluggable steps |
| `mc-workflow-ui-diagram` | Diagram rendering of a workflow |
| `mc-workflow-ui-diagram-app` | Standalone diagram app |
| `mc-workflow-swing-ui` | Swing-based workflow editor |
| `mc-workflow-test` | Shared test fixtures |

## Build & test

```bash
# Whole tree
mvn -f workflow/pom.xml clean install -DskipTests

# One module's tests
mvn -f workflow/mc-workflow/pom.xml test
```

Java 21.
