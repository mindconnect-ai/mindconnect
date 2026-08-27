---
title: Step reference
sidebar_position: 4
---

# Step reference

Every step is a `StepData` subclass. In JSON the `@class` field is the type
discriminator (the fully-qualified class name); in Java you instantiate the
class directly. All steps share two base fields:

| Field | Meaning |
|-------|---------|
| `name` | Step name (unique within its container) |
| `assignResultToVar` | Optional — variable that receives the step's result |

String values support `${variable}` substitution, and condition/assignment
expressions support the built-in `mini:` language and any other registered
scripting prefix (`groovy:`, `javascript:`, …).

:::warning Conditions need a language prefix
A condition **must** carry a language prefix, e.g. `mini: score >= 90`. A bare
`score >= 90` is not evaluated — the resolver returns the raw string, `If`
treats it as false and `Halt` never halts. `${var}` substitution alone is not
enough for `Halt` either: it produces a *string*, and `Halt` only reacts to a
real boolean `true`.
:::

---

## AssignVariables

Sets one or more variables from literals or expressions.

```json
{
  "@class": "ai.mindconnect.workflow.domain.AssignVariablesData",
  "name": "greet",
  "assignResultToVar": "greeting",
  "variableAssignments": [
    { "varName": "greeting", "expressionOrVarName": "Hello ${name}!" }
  ]
}
```

```java
var step = new AssignVariablesData();
step.setName("greet");
step.getVariableAssignments()
    .add(new VariableAssignment("greeting", "Hello ${name}!"));
```

---

## Block

Groups a list of steps; its result is the result of its last step.

```json
{
  "@class": "ai.mindconnect.workflow.domain.BlockData",
  "name": "outer",
  "steps": [
    { "@class": "ai.mindconnect.workflow.domain.AssignVariablesData", "name": "a",
      "variableAssignments": [ { "varName": "x", "expressionOrVarName": "1" } ] }
  ]
}
```

```java
var inner = new AssignVariablesData();
inner.setName("a");
inner.getVariableAssignments().add(new VariableAssignment("x", "1"));

var block = new BlockData();
block.setName("outer");
block.addSteps(inner);
```

---

## If

Branches on a condition. `conditions` is an **array** — the first condition that
evaluates true wins (if/else-if) — and there is an optional `elseBlock`.

```json
{
  "@class": "ai.mindconnect.workflow.domain.IfData",
  "name": "branch",
  "conditions": [ { "condition": "mini: score >= 90",
                    "thenBlock": { "@class": "ai.mindconnect.workflow.domain.BlockData",
                                   "name": "pass", "steps": [] } } ],
  "elseBlock": { "@class": "ai.mindconnect.workflow.domain.BlockData",
                 "name": "fail", "steps": [] }
}
```

```java
var then = new BlockData(); then.setName("pass");
var els  = new BlockData(); els.setName("fail");

var cond = new IfData.Condition();
cond.setCondition("mini: score >= 90");
cond.setThenBlock(then);

var ifStep = new IfData();
ifStep.setName("branch");
ifStep.setConditions(cond);
ifStep.setElseBlock(els);
```

---

## ForEach

Iterates a collection. Set `parallel` to run iterations concurrently, and
`joinResults` / `joinDelimiter` to collect the per-item results.

| Field | Meaning |
|-------|---------|
| `loopOver` | **Name of the variable** holding the collection (a bare name like `items`, *not* `${items}` — a `${…}` expression stringifies the list and fails with "not iterable") |
| `runVar` | Variable holding the current item |
| `indexVar` | Optional — current index |
| `parallel` | Run iterations concurrently |
| `joinResults`, `joinDelimiter` | Collect/join the per-item results |
| `resultFrom` | Optional — expression/variable evaluated per iteration as that iteration's result |

```json
{
  "@class": "ai.mindconnect.workflow.domain.ForEachData",
  "name": "loop",
  "loopOver": "items",
  "runVar": "item",
  "parallel": false,
  "joinResults": true,
  "joinDelimiter": ", ",
  "steps": []
}
```

```java
var loop = new ForEachData();
loop.setName("loop");
loop.setLoopOver("items");
loop.setRunVar("item");
loop.setJoinResults(true);
loop.setJoinDelimiter(", ");
```

---

## Code

Runs a script in a pluggable language (requires the matching
`mc-workflow-code-*` module).

| Field | Meaning |
|-------|---------|
| `language` | `mini` (built-in, no extra module), `javascript` (default), `groovy`, `beanshell`, `jython` |
| `code` | The script source |
| `injectVariables` / `exportVariables` | Bind workflow vars in/out (default true) |
| `wrapInFunction` | Wrap the code in a function (default false) |

```json
{
  "@class": "ai.mindconnect.workflow.domain.CodeData",
  "name": "square",
  "language": "javascript",
  "code": "result = base * base;",
  "assignResultToVar": "result"
}
```

```java
var code = new CodeData();
code.setName("square");
code.setLanguage("javascript");
code.setCode("result = base * base;");
code.setAssignResultToVar("result");
```

---

## HttpCall

Makes an HTTP request and exposes the response.

| Field | Meaning |
|-------|---------|
| `url`, `method` | Request URL and method (default `GET`) |
| `headers` | Map of request headers |
| `body`, `contentType` | Request body and its content type |
| `failOnError` | Throw on non-2xx (default true) |
| `timeoutMs` | Request timeout (0 = use the client default) |
| `statusCodeVar`, `responseHeadersVar` | Variables for status/headers |

```json
{
  "@class": "ai.mindconnect.workflow.domain.HttpCallData",
  "name": "fetch",
  "url": "${apiBase}/hello",
  "method": "GET",
  "assignResultToVar": "response"
}
```

```java
var http = new HttpCallData();
http.setName("fetch");
http.setUrl("${apiBase}/hello");
http.setMethod("GET");
http.setAssignResultToVar("response");
```

---

## CallWorkflow

Invokes another workflow by name, resolved from the
`WorkflowDefinitionRegistry` configured on the context (without a registry the
step throws). Use `assignParams` to feed parameters into the called workflow:
key = param name in the child, value = variable name or expression in the
current scope.

```json
{
  "@class": "ai.mindconnect.workflow.domain.CallWorkflowData",
  "name": "delegate",
  "workflow": "child-workflow",
  "assignParams": { "name": "userName" },
  "assignResultToVar": "childResult"
}
```

```java
var call = new CallWorkflowData();
call.setName("delegate");
call.setWorkflow("child-workflow");
call.addAssignParam("name", "userName");
call.setAssignResultToVar("childResult");
```

---

## JumpTo

Jumps execution to another step by name — the building block for loops and
gotos.

```json
{ "@class": "ai.mindconnect.workflow.domain.JumpToData",
  "name": "again", "jumpTo": "start" }
```

```java
var jump = new JumpToData();
jump.setName("again");
jump.setJumpTo("start");
```

---

## Halt

Pauses or stops the workflow. Optionally conditional, and can return a result.
A halted instance can later be resumed (see persistence).

| Field | Meaning |
|-------|---------|
| `condition` | Optional — only halt if it evaluates true. Needs a language prefix (`mini: needsApproval`); a bare comparison or a `${…}` substitution yields a string, and Halt only reacts to a real boolean `true` |
| `returnResult`, `returnResultExpression` | Whether/what to return on halt (`returnResult` defaults to `true`) |
| `resumeParams` | Optional `Schema` declaring the inputs expected when the workflow is resumed |
| `next` | Step to resume at |

```json
{
  "@class": "ai.mindconnect.workflow.domain.HaltData",
  "name": "wait-for-approval",
  "condition": "mini: needsApproval",
  "next": "after-approval"
}
```

```java
var halt = new HaltData();
halt.setName("wait-for-approval");
halt.setCondition("mini: needsApproval");
halt.setNext("after-approval");
```

---

Need a step that isn't here? See **[Custom steps](./custom-steps.md)** — you can
add your own without touching the engine.
