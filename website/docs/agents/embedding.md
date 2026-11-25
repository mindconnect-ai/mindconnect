---
title: Embedding the runtime
description: Build an agent runtime in plain Java with one builder — the engine, the default capabilities, and the plugins you add.
---

# Embedding the runtime

`mc-agent-sdk` assembles an `AgentRuntime` without Spring: one dependency and
one builder that put the engine and the default capabilities together in the
process, with agents, tools and skills handed over in code.

```java
AgentDefinition assistant = new AgentDefinition("assistant", "Helps.", "You help.",
        ModelSettings.ofModel("gpt"), ToolBinding.allOf("get_weather"), 10, Map.of());

try (AgentRuntime runtime = AgentRuntime.builder()
        .model(chat)                     // an LlmChat, or a ModelGateway of your own
        .agent(assistant)
        .tool(new WeatherTool())
        .build()) {
    Session session = runtime.sessions().open(new SessionSpec(UserId.of("me"), null, assistant, Map.of()));
    TurnId turn = runtime.turns().submit(TurnRequest.text(session.id(), "Weather?"));
    // the turn runs in the background: runtime.turns().subscribe(turn, listener) follows it,
    // runtime.turns().find(turn) says how it stands and, once it ended, what it answered
}
```

An agent belongs to its session: a turn names the session and runs as the
session's agent (`runtime.sessions().changeAgent(…)` changes it). Everything
else is asked of the runtime — `runtime.sessions()`, `runtime.turns()`, and
`runtime.service(X.class)` for whatever a plugin provides.

## What the builder takes

| Call | What it sets |
|---|---|
| `model(LlmChat)` / `model(ModelGateway)` | the model the turns ask; needed unless a plugin provides one |
| `agent(…)`, `tool(…)`, `skill(…)` | agents another agent may call by name, tools and skills, handed over in code |
| `inputGuard(…)`, `outputGuard(…)` | what is checked before a message reaches the model and before an answer leaves |
| `permission(tool, effect)`, `unruledTools(effect)` | whether a tool call runs, asks first or is refused (`ALLOW`, `ASK`, `DENY`); tools no rule names are allowed by default |
| `approvalMemory(…)` | where "allow for this session" is remembered; in memory by default |
| `scope(…)` | for a host that serves several namespaces and knows its users: a `ScopeSupplier` saying the namespace of the call, who acts in it, and whatever else the host carries along (`Scope` attributes). A turn and everything it starts run in the scope the turn was submitted in |
| `taskQueue(…)` | the queue turns and tool calls run on, for a host that brings its own — one on a database, one it also watches; in memory by default, closed with the runtime |
| `sessionStore(plugin)` | where sessions are kept; in memory by default |
| `plugin(…)` | anything else, see below |
| `without(Class)` | leaves a default capability out |

The default capabilities, each a plugin of `mc-agent-runtime-capabilities`:
the prompt with its variables, skills, tool search, sub-agents (`run_agent`,
`run_agents`, `list_agents`), permissions, [memory](./memory.md), traces, input
and output guards, response reviewers and session titles. **A plugin of a kind
the builder installs itself takes that one's place**, in the same position —
`.plugin(new SubAgentPlugin(inherited, 3))` configures the sub-agents instead
of adding a second set — and every other plugin comes after the defaults.

## What an installation adds

A runtime that keeps its agents, sessions and LLM configurations, reads the
tools from the classpath and serves several users is the same builder with
more plugins. They live in `mc-agent-runtime-infrastructure` (and its `-pg`
sibling) and in `mc-agent-management`:

| Plugin | Brings |
|---|---|
| `FileStorePlugin`, `InMemoryStorePlugin`, `PostgresStorePlugin` | every repository the others ask for — LLM configs and prices, messages, sessions, agents, skills, traces, todo lists, the users' memory, workflows — on files under a data directory, in memory, or in one Postgres database |
| `LlmPlugin` | the stored LLM configurations as models: a gateway per provider family behind one chat that routes by the configuration's name, embeddings, transcription, credentials encrypted with a key, a trace of every provider call — and the runtime's model on top |
| `ToolRegistryPlugin` | the tool registry over the `mc-agent-tools-*` modules on the classpath, less the tools switched off, with the operator's settings laid over them; a tool asks its environment for a service of the runtime, a service of the host, or a setting |
| `FilePlugin` | the file store uploads go into, the vector stores, and the reader that hands a model the bytes of a file |
| `ManagementPlugin` | `AgentManagement` and `SessionManagement` over the stores, the stored agents as the agents found by name, a session's status (`IDLE`, `BUSY`, `WAITING`) and what a user is told about their sessions (`SessionManagement.lifecycle`) |
| `DirectoryPlugin` | each user's home, the directories a session may work in, and what the model is told about them |
| `SkillSourcePlugin` | [skills](./skills.md) from the store, the user's directory and the project |
| `TokenizerPlugin` | token counts from a tokenizer instead of an estimate |
| `AttachmentPlugin`, `ToolResultPlugin`, `TodoPlugin`, `UserMemoryPlugin` | files attached to a session, cutting long tool results and reading them again, the todo list, what agents remember about a user |

The stores work in the namespace of the call: they are handed a
`ScopeSupplier`, which the host provides as a service. A runnable example with
in-memory stores, stored LLM configurations and the tools on the classpath is
`DemoRuntime` in `agents/demo/mc-agent-simple-demo`:

```java
AgentRuntime runtime = AgentRuntime.builder()
        .plugin(scope)                                       // provides the ScopeSupplier
        .plugin(new InMemoryStorePlugin(tempDir, 50))
        .plugin(new LlmPlugin(null, EnvVarResolver.system(), json))
        .plugin(new ToolRegistryPlugin(toolSettings, null, type -> Optional.empty()))
        .plugin(new ManagementPlugin())
        .plugin(new TodoPlugin())
        .plugin(new UserMemoryPlugin())
        .build();
runtime.service(ToolRegistryPlugin.class).warmUp();          // the tools bind their providers
runtime.service(LlmConfigRepository.class).save(config);
runtime.service(AgentManagement.class).create(ManagedAgent.of(definition));
```

## Tools that need an approval

A tool call that has to ask first — a binding marked `needsApproval`, an `ASK`
rule — does not end the turn. The turn waits, nothing holds a thread, and
`runtime.turns().find(turn)` shows what it waits for:

```java
Turn waiting = runtime.turns().runningTurn(session.id()).orElseThrow();
for (Interruption open : waiting.interruptions()) {
    runtime.turns().resume(waiting.id(), List.of(askUser(open)
            ? InterruptionAnswer.approve(open.id())          // approveAlways: for the rest of the session
            : InterruptionAnswer.reject(open.id(), "not now")));
}
```

The same turn goes on from there — until it completes or asks again — so the
question may be answered from anywhere, much later. A question a sub-agent
raises reaches the turn you submitted. The session's stream tells the moment
a turn starts to wait: an `approval_requested` for each tool call that asks,
then `waiting`.

### Following a session

A session has one stream. It tells all of what happens in it — the answer as
it is written, the tool calls, a question a turn stops for, how a turn ends —
and, when asked, of the sessions of its sub-agents too:

```java
runtime.sessions().stream(sessionId, true, event -> {
    switch (event.known()) {
        case TurnStreamEvent.TextDelta token -> print(token.text());
        case TurnStreamEvent.ToolCallStarted call -> show(call.toolName(), call.arguments());
        case TurnInterruptionEvent.ApprovalRequested asked -> ask(asked);
        case SessionStreamEvent.Done done -> finish(done.text());
        default -> { }
    }
});
```

An event is a `SessionEvent`: a `kind`, the session and the turn it is from,
the turn that started that one (`parentTurnId`, set for what a sub-agent
does), and `details` — plain values, so the event is the same on a channel,
in a buffer and on the wire. `event.known()` gives the runtime's own kinds in
their typed form, `event.as(EventType.TEXT_DELTA)` one of them. The typed forms are
`StreamEvent`s of two families: what the agent loop reports of a turn
(`TurnStreamEvent` — the model is asked, the answer, tool calls, messages,
cost, and as a `TurnInterruptionEvent` a question the turn stops for) and
what the runtime adds about the turn as a whole (`SessionStreamEvent` — it
stands still, the end, sub-agents). Every one of them
names its `kind()` and writes its values itself (`toDetails()`).

| Kind | Tells |
|------|-------|
| `asking_llm` | the model is asked — before every call of the model |
| `text_delta`, `thinking` | a piece of the answer, a piece of the model's reasoning |
| `tool_call_started`, `tool_call_result`, `tool_call_failed` | a tool call and how it came out |
| `approval_requested`, `input_requested` | the turn stops to ask — a tool call that needs an approval, or anything else |
| `waiting` | the turn has asked everything of this stop and stands still |
| `resumed` | something the turn stood still for has been answered: it goes on |
| `turn_usage` | what the turn has cost |
| `done`, `error` | the turn came to its end, or broke off |
| `sub_agent_started`, `sub_agent_done` | a turn another turn started begins and ends |
| `message` | a whole message is in the conversation |

A plugin tells of something of its own with an event of its own kind, through
`SessionEventPublisher` — `session.title`, `review.started`. It reaches the
same stream; a client that does not know the kind passes it by
(`SessionStreamEvent.Other`).

The stream tells what happens from now on. A client that opens while a turn
runs asks `runtime.turns().runningTurn(sessionId)`: the turn that has not
ended, with its status. Over REST a session carries that as `turnId` and
`turnStatus`. Whoever submitted a turn and follows that one turn to its end
subscribes to the turn (`runtime.turns().subscribe(turnId, …)`): the same
events, of that turn and the turns it starts, from the first — also for a
listener that comes late.

Events are told inside the process that runs the turn. With a task queue
shared by several nodes, a listener on another node hears nothing;
`runningTurn` is right on every node.

### Following a user's sessions

A list of sessions follows no stream. Which sessions are busy and which wait
is a session's `status`, read with the session
(`SessionManagement.list`, `find`), and what changes about them the
management tells:

```java
runtime.service(SessionManagement.class).lifecycle(userId, event -> {
    switch (event) {
        case SessionLifecycleEvent.Opened opened -> addRow(opened.sessionId());
        case SessionLifecycleEvent.Renamed renamed -> rename(renamed.sessionId(), renamed.title());
        case SessionLifecycleEvent.StatusChanged changed -> badge(changed.sessionId(), changed.status());
        case SessionLifecycleEvent.Deleted deleted -> removeRow(deleted.sessionId());
    }
});
```

That is the management's, not the runtime's: it hears every session as a
plugin does and makes the few things a list shows of it. A runtime without
`ManagementPlugin` has no such feed — a host that wants one of its own
contributes a `SessionEventListener`.

## Writing a plugin

A plugin provides services, contributes to the lists the runtime collects, and
hooks into what the runtime does:

```java
public final class AuditPlugin implements Plugin {

    @Override public String name() { return "audit"; }

    @Override public void install(PluginRegistry registry) {
        registry.provide(AuditLog.class, () -> new FileAuditLog(dir));          // one answer per type, made on first use
        registry.contribute(ToolResolver.class, new AuditTools());               // many of a kind: every plugin's is kept
        registry.listen(RuntimeHook.TOOL_CALL, (invocation, output) ->           // heard, without a say
                registry.get(AuditLog.class).called(invocation.call().toolName()));
        registry.after(RuntimeHook.INSTRUCTIONS, (context, text) -> text + "\n\nEvery tool call is logged.");
    }
}
```

- Services are **lazy singletons**: `provide` takes a supplier, `get` and
  `find` resolve it once. Providing a type again replaces the earlier answer;
  to decorate one, ask for it first and provide your wrapper around it.
- The hook points are in `RuntimeHook`: `INSTRUCTIONS`, `TOOL_OFFER`,
  `TOOL_CALL`, `MODEL_CALL`, `ANSWER`, `TURN_INPUT`, `TURN_END` — each with
  `before`, `after`, `around` and `listen`.
- What a plugin needs of another it asks the registry for, on first use — so
  the order of installation matters only where one replaces another's service
  or adds to the prompt after it.

### Settings a plugin reads

A plugin reads named settings instead of taking them in its constructor. It
declares them, so the host knows which exist:

```java
public final class AuditPlugin implements Plugin {

    static final Setting<Path> DIR = Setting.path("audit.dir", "where the audit log is written");
    static final Setting<String> PERSISTENCE =
            Setting.text("audit.persistence", "where audit records are kept")
                    .sharedWith("persistence").defaultsTo("file");

    @Override public List<Setting<?>> settings() { return List.of(DIR, PERSISTENCE); }

    @Override public void install(PluginRegistry registry) {
        registry.provide(AuditLog.class, () -> new FileAuditLog(registry.setting(DIR)));
    }
}
```

- A setting **has a default or is required**. A required one that is not set,
  or one set to text that cannot be read, stops the host when it starts — all
  of them named at once, each with its plugin.
- `sharedWith` makes a setting stand in for a shared one: `audit.persistence`
  where it is set, `persistence` otherwise, then the default.
- `whenNeeded()` is for a setting one case needs — a directory only when
  things are kept on files. It is not checked at the start and is an error
  where it is read and missing.
- The values come from a `SettingSource` the host provides before it installs
  its plugins: `registry.provide(SettingSource.class, () -> SettingSource.of(map))`.
  Without one every setting has its default. A key that is set and that no
  installed plugin declared is logged as a warning when the host starts.
- Settings are text, numbers, switches and paths, fixed when the host starts.
  An object — a data source, a clock — is a service, not a setting.

## The same runtime in Spring Boot

The server applications build their runtime the same way.
`mc-agent-starter-runtime-engine` is a Spring Boot starter whose
auto-configuration runs the builder: the stores by `mindconnect.persistence`
(`file`, the default, `postgres` or `memory`), the scope from the namespace
starter — so the runtime works in the namespace of each request — and the
settings are the `mindconnect.*` properties they always were
(`mindconnect.tools.*`, `mindconnect.agent.*`, `mindconnect.vector-store.*`,
`mindconnect.task-queue.*`, …). The starter hands the runtime's services to the
context — a controller injects `AgentManagement`, `LlmConfigRepository`,
`ToolRegistry`, … — each of those beans being nothing but
`runtime.service(X.class)`; and every bean of the context is a fallback for the
runtime's tools — a tool from an optional module finds the host's MCP gateway
without the runtime naming it.

An application contributes to the runtime with a bean:

```java
@Bean Plugin audit() { return new AuditPlugin(); }      // installed after the starter's own
```

and reaches it as `AgentRuntime` — `runtime.service(AuditLog.class)`,
`runtime.turns()`, `runtime.sessions()`.
