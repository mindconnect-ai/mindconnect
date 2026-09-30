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
    default String group() { return "general"; } // catalog rubric
    default void bind(ToolEnvironment env) {}   // grab dependencies once at startup
    default boolean isAvailable() { return true; } // disable when config is missing
    default Map<String, Object> overridesSchema() { return Map.of(); } // per-agent config knobs
    Tool create(AgentTool agentTool, ToolCallScope scope);
}
```

`group()` decides where the tool appears in the Admin UI catalog and in the
chat's tool picker. `overridesSchema()` describes the keys
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

## Asking the user for something: `userVariables()`

Some tools cannot be configured once for everybody. An API key an installation
buys is one value in the server's environment; a *mailbox* is one per person,
and nobody but its owner can supply it.

A tool source therefore **declares** what it needs from a user, and the
installation does the asking:

```java
@Override
public List<ToolVariable> userVariables() {
    return List.of(
        ToolVariable.required("MC_EMAIL_HOST", "Mail server", "Your IMAP server, e.g. imap.gmail.com."),
        ToolVariable.secret("MC_EMAIL_PASSWORD", "Mailbox password", "An app-specific one where your provider offers it."),
        ToolVariable.withDefault("MC_EMAIL_PORT", "Mail port", "993 for IMAP over TLS.", "993"),
        ToolVariable.optional("MC_SMTP_HOST", "SMTP server", "Leave empty and the mailbox is read-only."));
}
```

What each kind means:

| Factory | Required | Secret | Has a default |
|---------|----------|--------|---------------|
| `required(…)` | yes | no | no |
| `secret(…)` | yes | yes | no |
| `optionalSecret(…)` | no | yes | no |
| `withDefault(…, value)` | no | no | yes |
| `optional(…)` | no | no | no |

What the installation does with it, on a user's first request after signing in:

- a variable with a **default** that neither the user, their namespace nor the
  process has is **written for them**, so the common case needs no typing;
- every declared variable is listed on their profile under *Your variables*,
  with a **Set** action that opens the form already named;
- every **required** one that nobody has a value for raises a
  [notification](./admin-ui/index.md#notifications) pointing at that page.

Two rules worth knowing:

- **Declaring is not reading.** At call time the value still comes from the
  `EnvVarResolver` chain like any other `${VAR}` — the user's own first, then
  the namespace's, then the process's. Ask for it *per call*, never at `bind`
  time: a resolver answers for whoever the current call runs for, so a value
  captured in a field would be the first caller's.

  ```java
  MailAccount account = MailAccount.from(env.require(EnvVarResolver.class));   // per call
  ```

- **Nothing is ever created empty.** A blank user variable would *answer* the
  lookup and cut off the namespace and the server behind it, so a value that
  cannot be guessed is asked for rather than invented.

`isAvailable()` is the wrong place for "has this user configured it?": it is
decided once, at bind time, for the whole installation, and the first user to
sign in would decide it for everybody. Register the tool either way and return
an error result that says which variable is missing and where to put it — the
user reads it in the chat and can act on it.

## Running on the user's own account: `connectionSpec()`

A variable is right when an operator could set the value once for everybody.
A **mailbox** is not: it is one account per person, and a person may have two.
That is what a *connection* is.

Declare what has to be connected, and how a user comes by it:

```java
@Override
public ConnectionSpec connectionSpec() {
    return ConnectionSpec.form("email", "Mailbox", Schema.object()
            .prop("host",     Schema.string().description("Your IMAP server, e.g. imap.gmail.com"))
            .prop("port",     Schema.integer().defaultValue(993))
            .prop("user",     Schema.string())
            .prop("password", Schema.string().format(Schema.Format.PASSWORD))
            .require("host", "user", "password"))
        .description("The mailbox the email tools read.")
        .allowingSeveral();
}
```

That declaration is the whole user interface. The **Connections** tab on the
profile renders the card and the form from it, the sign-in check turns it into
a notification, and `Format.PASSWORD` decides what is encrypted at rest and
never shown again. A tool ships no screen and needs no dependency on the Admin
UI — which is what lets a tool module live in another repository.

For a service with a real sign-in, declare that way too — both can sit on one
card, "Connect with Google" beside "Add manually":

```java
ConnectionSpec.form("microsoft", "Microsoft", schema())
        .acquire(Acquisition.OAuth.of("ms-graph", "Mail.Read", "Calendars.ReadWrite", "offline_access"));
```

`"ms-graph"` names an **app registration** the installation holds, not the
connection: the operator registers it once, every user signs in to their own
account through it, and the token is renewed on its way to a tool. Ask for
`offline_access` (or its equivalent) or there will be no refresh token and the
connection dies at the first expiry.

`provider` (`"email"`) is **not** the tool group. Three bundles — mail,
calendar, files — can share one `"microsoft"` connection, and keeping the two
apart is what makes that possible.

### Getting the connection: `ConnectedTool`

The tool is *handed* the account rather than looking it up:

```java
public final class ListMessagesTool implements ConnectedTool {

    @Override
    public String execute(Map<String, Object> arguments, BoundConnections connections) {
        ToolConnection account = connections.one();
        String host = account.value("host");          // settings and secrets alike
        String password = account.value("password");
        ...
    }
}
```

A decorator around the tool does four things, once, so no tool repeats them:

1. puts an `account` parameter into the schema — an **enum of exactly this
   user's connections** — or leaves it out when there is nothing to choose;
2. takes that parameter back out of the arguments;
3. resolves it: the named connection, else the user's default;
4. answers a call that has no usable account **without entering the tool**,
   with the sentence that says where to attach one.

A tool with two ends declares two parameters and reads them by name:

```java
ConnectionSpec.form("calendar", "Calendar", schema)
        .params(ConnectionParam.of("from", "calendar"), ConnectionParam.of("to", "calendar"));
// connections.get("from"), connections.get("to")
```

### Several accounts, and how an agent pins one

A user with two mailboxes sees `account: "privat" | "arbeit"` and says which
one they mean. An agent can settle it instead, with the mechanism that already
exists for carrying one tool twice:

```json
"tools": [
  { "name": "email_work_list_messages",
    "overrides": { "tool": "email_list_messages", "params": { "account": "arbeit" } } }
]
```

`AliasTool` gives it its own name, `PinnedParamsTool` fixes the account and
takes it out of the schema. Note that a connection key is **personal**: pin one
only in an agent that belongs to one person, never in a definition several
people share.

That is also exactly what a user does for themselves on the
[My tools](./admin-ui/index.md#my-tools) tab — `UserToolRoster` derives the same
two overrides from *their* connections, and the runtime applies them to the
agent they are chatting with. A tool does nothing to support it.

## Scope-aware tools

`create(AgentTool, ToolCallScope)` runs once per tool resolution, so a tool can
be bound to the current call. The `ToolCallScope` carries things like the
session id — useful for tools whose state is per-conversation (the todo list
above passes `scope.sessionId()` into the tool).

### Times: the user's zone, not the server's

A tool that reads or writes a local time — "16:16", "2026-09-22" — asks for
the zone of the user the call runs for, never `ZoneId.systemDefault()`: the
server often runs in UTC, and a train at 16:16 in Zurich would otherwise be
booked two hours late. Take the resolver in `bind`, ask it in `execute`, so a
zone the user changes on their profile page applies to the next call:

```java
private TimeZones zones = TimeZones.system();

@Override public void bind(ToolEnvironment env) {
    zones = TimeZones.of(env);            // the host's resolver, the JVM's zone without one
}

@Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
    return new MyTool(() -> zones.zoneOf(scope.userId()));
}
```

Show times back in the same zone, so the model reads what it wrote. The
system prompt already tells the model which zone that is (see
[the user's time zone](./prompt-renderer.md#the-users-time-zone)).

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

The lifecycle mirrors `ToolFactory` (no-arg constructor → `bind` →
`isAvailable` → `create` per resolution), and registration is the same
ServiceLoader mechanism with
`META-INF/services/ai.mindconnect.agent.tool.MultiToolProvider`.

**`bind` runs off the startup path, after the host is up.** A provider may have to reach outside the
process — spawn a container, ask a remote catalog — and that is no reason for
an application to wait. The registry starts every provider's `bind` on its own
virtual thread and gives the first round a couple of hundred milliseconds
before it carries on, which is plenty for the in-process providers and not
enough for the others. Whoever misses that window joins the catalog the moment
`isAvailable()` turns true; `toolNames()` and `isAvailable()` are asked live on
every lookup, so nothing has to be told. A `bind` that throws, or one that
leaves the provider unavailable, is tried again a few times over about a
minute — the case worth catching is a container runtime that starts alongside
the application rather than before it. Two consequences for a provider author:
`bind` may run concurrently with the first lookups, so publish your state
safely, and it may run more than once, so make it repeatable. A host that starts in phases can take the warm-up into
its own hands — the Spring runtime builds the registry deferred and starts it
when the context is refreshed, so a provider asking the container for a
service never queues behind the container's own startup.

Two things are specific to providers:

- **`toolNames()` and `isAvailable()` are consulted on every lookup** (catalog,
  admin-UI dropdown, name resolution), so a provider backed by mutable data
  re-reads its source there instead of caching in `bind` — keep both cheap (an
  in-memory map or a directory listing, not a network round-trip, and
  certainly not a health check). A provider that throws from `isAvailable()`
  is taken as unavailable and logged, rather than failing the lookup for
  everyone else.
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
4. Needs something only the user can supply? Declare it — a value with
   [`userVariables()`](#asking-the-user-for-something-uservariables), a whole
   account with [`connectionSpec()`](#running-on-the-users-own-account-connectionspec).
5. Put the module on the agent runtime's classpath.

The new tool now appears in the **[Tools](./admin-ui/tools.md)** catalogue and can be
assigned to any agent.
