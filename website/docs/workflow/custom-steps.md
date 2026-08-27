---
title: Custom steps
sidebar_position: 5
---

# Writing a custom step

You can add your own step type **without modifying the engine**. A step is two
classes plus one registration call:

1. A **`StepData`** subclass — the configuration (the JSON/Java model).
2. A **`StepInstance`** — the execution logic (extend `BaseStepInstance`).
3. **Register** the mapping on the `StepInstanceFactory`.

No reflection, no fork of the runtime — the factory is the extension point.

## 1. The configuration: a `StepData`

Extend `BaseStepData` (which already carries `name` and `assignResultToVar`) and
add whatever fields your step needs. Lombok `@Data` gives you the
getters/setters Jackson needs.

```java
package com.example.workflow;

import ai.mindconnect.workflow.domain.BaseStepData;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UppercaseData extends BaseStepData {
    /** Expression yielding the text to upper-case (supports ${...}). */
    private String input;
}
```

The `type` discriminator is derived automatically from the class name
(`UppercaseData` → `uppercase`).

## 2. The logic: a `StepInstance`

Extend `BaseStepInstance<YourData>`. It gives you `getConfig()` (your typed
config), `resolveExpression(...)` (handles `${...}` and `mini:`/script prefixes),
`getVariableScope()` (read/write workflow variables), and `setResult(...)`
(what the step returns and what `assignResultToVar` captures).

```java
package com.example.workflow;

import ai.mindconnect.workflow.execution.BaseStepInstance;

public class UppercaseStep extends BaseStepInstance<UppercaseData> {

    @Override
    public void execute() {
        Object value = resolveExpression(getConfig().getInput());
        String result = value == null ? "" : value.toString().toUpperCase();
        setResult(result);   // honoured by assignResultToVar
    }
}
```

## 3. Register it on the factory

The `StepInstanceFactory` maps a `StepData` class to its `StepInstance`
supplier. Grab the factory from your context factory and register the new
mapping — the supplier must return a **fresh** instance each call:

```java
var contextFactory = new DefaultWorkflowContextFactory();

contextFactory.getStepInstanceFactory()
        .register(UppercaseData.class, UppercaseStep::new);

var service = new WorkflowExecutorService(contextFactory);
```

That's it — the engine now executes `UppercaseData` steps. Built-in steps and
your custom one are treated identically from here on.

## Auto-register via SPI (no manual call)

If you package your step as its own module, you can have it register itself the
same way the built-in language modules do — so users just add your jar and get
the step, with no `register(...)` call. Implement a `WorkflowConfigurer`:

```java
package com.example.workflow;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

public class UppercaseConfigurer implements WorkflowConfigurer {
    @Override
    public void configure(WorkflowContextFactory factory) {
        factory.getStepInstanceFactory()
               .register(UppercaseData.class, UppercaseStep::new);
    }
}
```

Then declare it in a service file (one fully-qualified class name per line):

```
src/main/resources/META-INF/services/ai.mindconnect.workflow.execution.WorkflowConfigurer
```
```
com.example.workflow.UppercaseConfigurer
```

Now any app that builds its factory with `SpiWorkflowContextFactory.create()`
(from `mc-workflow-spi-lookup`) picks up your step automatically — same
mechanism the JavaScript/Groovy/Jackson modules use. See
[How it works → Auto-registration (SPI)](./how-it-works.md#auto-registration-spi).

## Use it

```java
var step = new UppercaseData();
step.setName("shout");
step.setInput("Hello ${name}");
step.setAssignResultToVar("loud");

var wf = new WorkflowData();
wf.setName("demo");
wf.addSteps(step);
wf.setResultFrom("loud");

var result = service.executeWorkflow(wf, Map.of("name", "Ada"));
// result.getResult() -> "HELLO ADA"
```

### As JSON

Because the JSON discriminator is the fully-qualified class name, a custom step
serializes just like a built-in one — point `@class` at your class:

```json
{
  "@class": "com.example.workflow.UppercaseData",
  "name": "shout",
  "input": "Hello ${name}",
  "assignResultToVar": "loud"
}
```

For Jackson to deserialize it, the class must be on the classpath and visible to
the object mapper (`WorkflowObjectMapperFactory.create()` resolves `@class`
names by reflection, so no extra registration is needed for round-tripping).

## Optional: PlantUML support

If you also want your step authorable from PlantUML, implement a `DslStepBuilder`
and register it via the SPI file
`META-INF/services/ai.mindconnect.workflow.dsl.puml.spi.DslStepBuilder`. That is
only needed for the diagram DSL — the Java and JSON paths work with just the two
classes and the factory registration above.
