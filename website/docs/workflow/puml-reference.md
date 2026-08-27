---
title: PlantUML reference
sidebar_position: 7
---

# PlantUML DSL reference

`mc-workflow-dsl-puml` parses a subset of the PlantUML activity-diagram syntax
into a `WorkflowData`, so a diagram *is* the workflow definition. This page is
the syntax reference; for parsing/executing see
[Getting started](./getting-started.md#or-author-it-in-plantuml).

```java
import ai.mindconnect.workflow.dsl.puml.PumlWorkflowParser;

WorkflowData wf = new PumlWorkflowParser().parse(pumlString);
// also: parse(InputStream), parse(Path)
```

## Document shape

```text
@startuml workflowName     ' the name after @startuml becomes the workflow name
:input -> name, limit;     ' declared input parameters
start                      ' optional structural markers (start/stop) are ignored
  ... steps ...
stop
:output <- result;         ' resultFrom — which variable is the workflow result
@enduml
```

- Comments start with a single quote: `' like this`.
- `:input -> a, b;` declares parameter names; `:output <- var;` sets
  `resultFrom`.
- `${var}` expressions **must be quoted** inside property lines — write
  `"${var}"`, otherwise PlantUML treats it as one of its own variables.

## Action steps

An action block is `:type name` followed by `prop = value` lines; the **last
property line ends with a semicolon**.

| Keyword | Step |
|---------|------|
| `assign` | AssignVariables |
| `code` | Code (scripting) |
| `httpcall` | HttpCall |
| `call` | CallWorkflow |
| `jump` | JumpTo |
| `halt` | Halt |

`assign-result -> varName` (on its own line) sets the step's
`assignResultToVar`.

### assign

```text
:assign setVars
  firstName = John
  lastName = Doe
  greeting = "Hello ${firstName}!"
  assign-result -> greeting;
```

### code

Requires a `mc-workflow-code-*` module on the classpath.

```text
:code square
language = javascript
code = base * base
assign-result -> result;
```

For multi-line scripts use a `note` block with a `code:` prefix:

```text
:code compute
language = javascript
assign-result -> sum;
note right
  code:
  var doubled = x * 2;
  var tripled = x * 3;
  doubled + tripled;
end note
```

### httpcall

```text
:httpcall fetch
url = "https://example.com/api/${id}"
method = POST
failOnError = false
statusCodeVar = status
header.Authorization = "Bearer ${token}"
assign-result -> response;
```

Headers are set with `header.<Name> = value`. A request body can be given via a
`note` block with a `body:` prefix:

```text
:httpcall postOrder
url = https://example.com/api/orders
method = POST
assign-result -> orderId;
note right
  body:
  { "userId": "${userId}", "amount": "${amount}" }
end note
```

### call (sub-workflow)

```text
:call invokeChild
workflow = childWf
userId = "${currentUser}"
assign-result -> childResult;
```

Define the sub-workflow with a `partition` block:

```text
partition childWf {
  :assign hello
  msg = hello from child;
}
```

:::warning Partitions need a registry to be callable
The parser stores partitions in `WorkflowData.subWorkflows`, but at runtime
`CallWorkflowStep` resolves the name **only** through the
`WorkflowDefinitionRegistry` configured on the context — it never looks at
`subWorkflows`, and without a registry it throws. Register the child workflow
(e.g. via `JacksonWorkflowDefinitionRegistry`) before executing.
:::

### jump and halt

```text
:jump skipToEnd
  to = endStep;
```

```text
:halt waitForApproval
condition = "mini: needsApproval"
next = afterApproval;
```

The halt condition needs a language prefix (`mini:`, `groovy:`, …). A plain
`${needsApproval}` substitution yields a *string*, and `Halt` only reacts to a
real boolean `true` — it would never halt.

## Control flow

### if / else

The condition supports expression prefixes — the built-in `mini:`, plus any
registered scripting language (`groovy:`, `javascript:`, …).

```text
if (mini: score >= 90) then (yes)
  :assign highTier
  tier = gold;
else (no)
  :assign lowTier
  tier = silver;
endif
```

`else` is optional (`endif` directly after the `then` block).

### for-each

Declare the loop inline, then put the body in a `fork` block:

```text
:for -> items as item index i parallel;
fork
  :assign processItem
    processed = "${item}"
    assign-result -> processed;
end fork
:output <- results;
```

- `as item` → `runVar`, `index i` → `indexVar`, `parallel` → run concurrently.
- Loop metadata can alternatively be declared in a `note` block immediately
  before the `fork` (`loopOver:`, `runVar:`, `indexVar:`, `parallel:`).

## A complete example

```text
@startuml order-flow
:input -> userId, amount;
start

:httpcall postOrder
url = https://example.com/api/orders
method = POST
assign-result -> orderId;
note right
  body:
  { "userId": "${userId}", "amount": "${amount}" }
end note

:code summarize
language = javascript
code = "Order " + orderId + " placed"
assign-result -> summary;

stop
:output <- summary;
@enduml
```

## Extending the DSL

New step keywords are supported **without changing the parser**: implement a
`DslStepBuilder` and register it via
`META-INF/services/ai.mindconnect.workflow.dsl.puml.spi.DslStepBuilder`. Any
`:keyword name` block whose keyword is registered is handed to your builder.
This is the same SPI idea as the engine's
[auto-registration](./how-it-works.md#auto-registration-spi).
