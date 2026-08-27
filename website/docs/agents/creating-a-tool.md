---
title: Creating a tool
sidebar_position: 5
---

# Creating a tool

A tool is a function the LLM can call. The usual way to add one is a small
module that contributes two things: a `Tool` (what runs) and a `ToolFactory`
(how the runtime discovers and builds it). Factories are found at startup via
the Java `ServiceLoader`, so **no central registry edit is needed** — drop the
module on the classpath and the tool shows up.

(For *dynamic bundles* — many tools from one source, like an MCP server —
there is a second SPI, [`MultiToolProvider`](#many-tools-from-one-source-multitoolprovider);
the Gmail and workflow tools use it. The main part of this page covers the
single-tool `ToolFactory` path.)

## The two interfaces

```java
public interface Tool {
    String name();
    String description();                       // shown to the LLM
    Map<String, Object> parametersSchema();     // JSON Schema of the arguments
    String execute(Map<String, Object> arguments);
}
```

```java
public interface ToolFactory {
    String name();                              // the tool name this factory owns
    default String group() { return "general"; } // catalog rubric + tool_search filter
    default void bind(ToolEnvironment env) {}   // grab dependencies once at startup
    default boolean isAvailable() { return true; } // disable when config is missing
    default Map<String, Object> overridesSchema() { return Map.of(); } // per-agent config knobs
    Tool create(AgentTool agentTool, ToolCallScope scope);
}
```

`group()` decides where the tool appears in the Admin UI catalog and which
`toolSearch.groups` filter matches it. `overridesSchema()` describes the keys
an agent may set in its tool binding's `overrides` map (e.g. `baseDir`) so the
UI can render a form for them.

## Example: a `word_count` tool

A minimal tool that counts the words in a string. Two classes plus one
service-loader file.

### 1. The tool

```java
package com.example.tools.wordcount;

import ai.mindconnect.agent.tool.Tool;
import java.util.Map;

public final class WordCountTool implements Tool {

    public static final String NAME = "word_count";

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "Count the number of words in a piece of text.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "text", Map.of(
                    "type", "string",
                    "description", "The text whose words should be counted."
                )
            ),
            "required", new String[]{"text"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String text = (String) arguments.getOrDefault("text", "");
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        return "Word count: " + words;
    }
}
```

### 2. The factory

```java
package com.example.tools.wordcount;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;

public final class WordCountToolFactory implements ToolFactory {

    @Override public String name() { return WordCountTool.NAME; }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new WordCountTool();
    }
}
```

### 3. Register it (the service-loader file)

Create a file at:

```
src/main/resources/META-INF/services/ai.mindconnect.agent.tool.ToolFactory
```

containing the fully-qualified factory class name (one per line):

```text
com.example.tools.wordcount.WordCountToolFactory
```

That's it. On startup the runtime loads every `ToolFactory`, and `word_count`
becomes available to any agent that lists it.

## Using dependencies

A tool rarely lives in isolation — it usually needs a repository, an HTTP
client, an API key, or a base directory. The factory pulls exactly what it
needs from the `ToolEnvironment` in `bind(...)`, and reports `isAvailable()`
based on whether those dependencies are present:

```java
public final class TodoReadToolFactory implements ToolFactory {

    private TodoListService service;

    @Override public String name() { return TodoReadTool.NAME; }

    @Override
    public void bind(ToolEnvironment env) {
        this.service = env.require(TodoListService.class);
    }

    @Override
    public boolean isAvailable() { return service != null; }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new TodoReadTool(service, scope.sessionId());
    }
}
```

`ToolEnvironment` is a typed lookup the host application populates with whatever
it wants to expose to tools:

```java
env.get(MyService.class);        // Optional<MyService>
env.require(MyService.class);    // throws if missing
env.getString("api-key");        // Optional<String>
env.requireString("base-dir");   // throws if missing
```

A factory should request **only** what it uses — there is no shared
god-context. If a required value is missing, return `false` from
`isAvailable()` and the tool is quietly left out instead of crashing the agent.

## Scope-aware tools

`create(AgentTool, ToolCallScope)` runs once per tool resolution, so a tool can
be bound to the current call. The `ToolCallScope` carries things like the
session id — useful for tools whose state is per-conversation (the todo list
above passes `scope.sessionId()` into the tool).

## Streaming results

If a tool's output should go straight to the user (not just back to the LLM),
override:

```java
@Override public boolean streamsResultToUser() { return true; }
```

## Many tools from one source: `MultiToolProvider`

`ToolFactory` is strictly 1:1 with a tool name. When one source contributes
**several** tools — an MCP-server bundle sharing one connection
(`gmail_search_messages`, `gmail_read_message`, …), or a dynamic set that
changes at runtime (one tool per persisted workflow) — implement the second
SPI instead:

```java
public interface MultiToolProvider {
    Set<String> toolNames();                    // what this provider serves *right now*
    default String group() { return "general"; } // rubric + name prefix convention
    default void bind(ToolEnvironment env) {}
    default boolean isAvailable() { return true; } // false hides the whole bundle
    Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope);
}
```

The lifecycle mirrors `ToolFactory` (no-arg constructor → `bind` once →
`isAvailable` → `create` per resolution), and registration is the same
ServiceLoader mechanism with
`META-INF/services/ai.mindconnect.agent.tool.MultiToolProvider`.

Two things are specific to providers:

- **`toolNames()` is consulted on every lookup** (catalog, admin-UI dropdown,
  name resolution), so a provider backed by mutable data re-reads its source
  there instead of caching in `bind` — keep it cheap (an in-memory map or a
  directory listing, not a network round-trip).
- **`group()` doubles as the name namespace**: by convention a provider's tool
  names compose as `group() + "_" + localName` — group `workflow`, workflow
  `pipeline` → tool `workflow_pipeline`; group `gmail` →
  `gmail_search_messages`.

`create` returns `Optional.empty()` for names it doesn't serve; the registry
then asks the next provider. `isAvailable() == false` (e.g. credentials
missing) makes the entire bundle disappear.

## Checklist

1. Implement `Tool` — `name`, `description`, `parametersSchema`, `execute`.
2. Implement `ToolFactory` — return your tool from `create(...)`; pull
   dependencies in `bind(...)`. (Several tools from one source? Implement
   `MultiToolProvider` instead.)
3. Add `META-INF/services/ai.mindconnect.agent.tool.ToolFactory` (or
   `…tool.MultiToolProvider`) with the class name.
4. Put the module on the agent runtime's classpath.

The new tool now appears in the **[Tools](./admin-ui/tools.md)** catalogue and can be
assigned to any agent.
