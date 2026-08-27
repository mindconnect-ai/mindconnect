---
title: Variable scope
sidebar_position: 6
---

# Variable scope

Workflow variables live in a **hierarchical store**. Every step container (the
workflow itself, a block, a for-each body, an if-branch) owns one
`VariableScope`, and each scope has a parent. Lookups walk **up** the parent
chain; writes land in a specific scope. Understanding this is the key to knowing
which step can see which variable.

## The hierarchy

```
 Workflow scope            ← input params land here
   └─ block "outer"        ← child scope
        └─ forEach "loop"  ← child scope (one per iteration)
             └─ if "branch"
```

Each scope is named after its step (the `name` you give the step). A scope
holds only the variables assigned *in* it; everything else is resolved through
its parent.

## Reading: lookup walks up

`getVariable(name)` checks the current scope, then its parent, then the
grandparent, and so on — returning the **first** match. So an inner step sees:

- variables it assigned itself,
- variables any enclosing step assigned,
- the workflow's input parameters (which live in the top scope).

A sibling step in a *different* branch does **not** see your local variables —
they aren't on its lookup path.

## Writing: two modes

There are two ways to assign, and the difference is what makes results visible
to later steps:

### `assignValue` — nearest-wins

Used for ordinary assignment. It first looks the name up the chain:

- **If the variable already exists** anywhere up the chain, that existing
  variable is updated in place — no shadowing copy is created.
- **If it doesn't exist anywhere**, a new variable is created in the **current**
  scope.

```
// 'total' was set by an enclosing block:
assignValue("total", 42)   → updates the enclosing block's 'total'

// 'temp' is new:
assignValue("temp", 1)     → creates 'temp' in the current (inner) scope
                             → invisible to steps outside this scope
```

### `assignValueToParentScope` — publish upward

Writes the variable into the **parent** scope explicitly (throws if there is no
parent). This is how a step makes its result visible to its siblings and the
steps that follow it, rather than burying it in its own short-lived scope.

Built-in steps use this for their outputs — for example `AssignVariablesStep`
publishes each assignment to the parent scope so later steps can read it.

## Step results → variables

Every step produces a **result**: inside its `execute()` the step calls
`setResult(value)` (for example `AssignVariablesStep` sets the last assigned
value, `HttpCallStep` sets the response, a `CodeStep` sets the script's return
value). What happens to that result depends on `assignResultToVar`:

```
step.execute()
   └─ stepInstance.getResult()            // the value the step produced
        └─ if assignResultToVar is set:
              variableScope.assignValue(assignResultToVar, result)
```

- **With `assignResultToVar`** — the engine writes the result into a variable of
  that name in the container's scope, using the same nearest-wins rule as
  `assignValue`: an existing variable up the chain is updated in place, otherwise
  a new one is created. Later steps can then read it as `${thatName}`.
- **Without `assignResultToVar`** — the result still exists as the step's result
  object (and can become a block's result, below), but it is **not** written to
  any named variable.

```json
{
  "@class": "ai.mindconnect.workflow.domain.HttpCallData",
  "name": "fetch",
  "url": "${apiBase}/user",
  "assignResultToVar": "user"
}
```

After this step runs, `${user}` holds the HTTP response and any following step
can use it.

## A container's result (`resultFrom`)

A workflow — and any block — produces its own result, controlled by its
`resultFrom` field:

- **`resultFrom` set** — the container looks up a variable with that name
  (walking up the chain). If found, its value becomes the container's result; if
  there is no such variable, `resultFrom` is evaluated as an expression.
- **`resultFrom` not set** — the container's result is `null`.

```java
var wf = new WorkflowData();
wf.setName("hello");
wf.addSteps(greet);          // greet writes 'greeting' via assignResultToVar
wf.setResultFrom("greeting"); // the workflow returns whatever 'greeting' holds

WorkflowResult result = service.executeWorkflow(wf, Map.of("name", "Ada"));
result.getResult();          // value of the 'greeting' variable
```

So the typical chain is: a step sets its result → `assignResultToVar` stores it
in a variable → the workflow's `resultFrom` names that variable as the value
returned by `WorkflowResult.getResult()`.

## Input parameters

The `params` you pass to `executeWorkflow(wf, params)` are assigned into the
**top-level workflow scope**, so every step in the workflow can read them. A
workflow declares its expected parameters via `WorkflowData.params` (a
`Schema`; use `declareParams("name", …)` for the simple name-list form).

The executor also injects a built-in variable **`env`** into the root scope of
every run — a map of the process environment variables merged with the system
properties — so steps can read `${env.HOME}` and friends.

```java
service.executeWorkflow(wf, Map.of("name", "Ada", "limit", 10));
// every step resolves ${name} and ${limit}
```

## Expressions resolve against the scope

`${var}` substitution and `mini:`/script expressions are evaluated against the
current step's scope, so they follow the same up-the-chain lookup. A `CodeStep`
with `injectVariables` (default) binds the visible variables into the script,
and with `exportVariables` (default) writes the script's variables back out.

## Practical rules of thumb

- **Need a value later?** Make sure the step that produces it publishes upward
  (use `assignResultToVar`, or assign to an outer variable name) — a value left
  only in an inner scope vanishes when that scope ends.
- **Want isolation?** Assign to a fresh name inside a block or for-each body; it
  stays local and won't collide with outer variables.
- **Parallel for-each:** each iteration runs in its own child scope, so
  iterations don't clobber each other's locals — use `joinResults` to collect
  their outputs.
