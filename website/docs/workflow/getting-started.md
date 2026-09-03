---
title: Getting started
sidebar_position: 3
---

# Getting started

This walks through running your first workflow — a tiny one that takes an input
parameter and produces a result.

## Add the dependency

```xml
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-workflow</artifactId>
  <version>0.4.0</version>
</dependency>
```

That's the whole engine. No Spring, no server.

## A simple workflow in Java

Build a `WorkflowData`, add a step that assigns a variable, and execute it:

```java
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;

import java.util.Map;

public class Demo {
    public static void main(String[] args) {
        // 1. One step: greeting = "Hello, ${name}!"
        var greet = new AssignVariablesData();
        greet.setName("greet");
        greet.getVariableAssignments()
             .add(new VariableAssignment("greeting", "Hello, ${name}!"));

        // 2. The workflow: one step, result taken from the 'greeting' variable
        var wf = new WorkflowData();
        wf.setName("hello");
        wf.addSteps(greet);
        wf.setResultFrom("greeting");

        // 3. Run it with input parameters
        var service = new WorkflowExecutorService(new DefaultWorkflowContextFactory());
        WorkflowResult result = service.executeWorkflow(wf, Map.of("name", "Ada"));

        System.out.println(result.isSuccess());   // true
        System.out.println(result.getResult());   // Hello, Ada!
    }
}
```

`${name}` is resolved from the input parameters; `setResultFrom("greeting")`
tells the workflow which variable to return as its result.

## Add a computed step (scripting)

To compute values, use a **script expression** — a value prefixed with a
language name, like `mini: base * base`.

### MiniScript — built in, no dependency

The engine always has the **MiniScript** language registered out of the box
(`language` name `mini`), so you can compute without adding anything:

```java
var square = new AssignVariablesData();
square.setName("square");
square.getVariableAssignments()
      .add(new VariableAssignment("result", "mini: base * base"));

var wf = new WorkflowData();
wf.setName("square");
wf.addSteps(square);
wf.setResultFrom("result");

var result = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
        .executeWorkflow(wf, Map.of("base", 7));
// result.getResult() -> 49.0  (MiniScript arithmetic always yields a Double)
```

MiniScript is a small, JSON-friendly expression language — see the
[MiniScript reference](./miniscript.md) for its full syntax.

### Other languages (Groovy, BeanShell, Jython)

For Groovy, JavaScript, BeanShell or Jython, add that language module **plus**
`mc-workflow-spi-lookup` (which auto-registers every module on the classpath).
For example, Groovy:

```xml
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-workflow-code-groovy</artifactId>
  <version>0.4.0</version>
</dependency>
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-workflow-spi-lookup</artifactId>
  <version>0.4.0</version>
</dependency>
```

Build the factory with `SpiWorkflowContextFactory.create()` — it discovers and
applies the Groovy module automatically, so there is **no manual
`configure(...)` call**. Then prefix the expression with `groovy:`:

```java
import ai.mindconnect.workflow.spi.SpiWorkflowContextFactory;

// Picks up mc-workflow-code-groovy (and any other module) from the classpath
var ctx = SpiWorkflowContextFactory.create();

var square = new AssignVariablesData();
square.setName("square");
square.getVariableAssignments()
      .add(new VariableAssignment("result", "groovy: base * base"));

var wf = new WorkflowData();
wf.setName("square");
wf.addSteps(square);
wf.setResultFrom("result");

var result = new WorkflowExecutorService(ctx)
        .executeWorkflow(wf, Map.of("base", 7));
// result.getResult() -> 49
```

`mc-workflow-code-javascript`, `-beanshell`, `-jython` and `mc-workflow-jackson`
register themselves the same way — no wiring code required. See
[How it works → Auto-registration (SPI)](./how-it-works.md#auto-registration-spi).

## Or author it in PlantUML

With `mc-workflow-dsl-puml`, the same assignment workflow is a diagram:

```text
@startuml hello
:assign greet
  greeting = Hello, ${name}!
  assign-result -> greeting
;
@enduml
```

```java
import ai.mindconnect.workflow.dsl.puml.PumlWorkflowParser;

WorkflowData wf = new PumlWorkflowParser().parse(pumlString);
var result = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
        .executeWorkflow(wf, Map.of("name", "Ada"));
```

## Or define it as JSON

With `mc-workflow-jackson` a workflow is a plain JSON document. Add the module:

```xml
<dependency>
  <groupId>ai.mindconnect</groupId>
  <artifactId>mc-workflow-jackson</artifactId>
  <version>0.4.0</version>
</dependency>
```

Write the workflow — the `@class` fields are the type discriminators that tell
Jackson which step type each node is (`hello.json`):

```json
{
  "@class": "ai.mindconnect.workflow.domain.WorkflowData",
  "name": "hello",
  "resultFrom": "greeting",
  "params": [ "name" ],
  "steps": [ {
    "@class": "ai.mindconnect.workflow.domain.AssignVariablesData",
    "name": "greet",
    "assignResultToVar": "greeting",
    "variableAssignments": [ {
      "varName": "greeting",
      "expressionOrVarName": "Hello ${name}!"
    } ]
  } ]
}
```

Read it with `JacksonWorkflowSerializer` and execute it like any other
`WorkflowData`:

```java
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;

import java.nio.file.Path;
import java.util.Map;

var serializer = new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create());

// read(String json), read(Path), read(InputStream), or readFromClasspath(...)
WorkflowData wf = serializer.read(Path.of("hello.json"));

var result = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
        .executeWorkflow(wf, Map.of("name", "Jackson"));
// result.getResult() -> Hello Jackson!
```

`serializer.write(workflowData)` does the reverse — turn a `WorkflowData` you
built in Java into JSON, so you can design in code and persist as JSON (or vice
versa).

## Next

- Add control flow with `IfStep` and `ForEachStep`.
- Call one workflow from another with `CallWorkflowStep`.
- Pause and resume with `mc-workflow-persistence`.

See [How it works](./how-it-works.md) for the full step catalogue.
