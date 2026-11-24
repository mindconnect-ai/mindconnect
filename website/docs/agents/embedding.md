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
| `namespaces(…)`, `users(…)` | for a host that serves several namespaces and knows its users: the namespace of the call and who acts in it |
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
| `ManagementPlugin` | `AgentManagement` and `SessionManagement` over the stores, and the stored agents as the agents found by name |
| `DirectoryPlugin` | each user's home, the directories a session may work in, and what the model is told about them |
| `SkillSourcePlugin` | [skills](./skills.md) from the store, the user's directory and the project |
| `TokenizerPlugin` | token counts from a tokenizer instead of an estimate |
| `UserStreamPlugin` | what a user's open pages hear of the runtime's turns |
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
raises reaches the turn you submitted. `runtime.sessions().subscribe(…)` tells
the moment a turn starts to wait (`SessionEvent.TurnInterrupted`).

### Following sessions

A subscription names which sessions it follows and how closely:

```java
// A list of sessions: which of the user's are busy, which wait for an answer.
runtime.sessions().subscribe(SessionEventFilter.user(userId), listener);

// The session on screen, as it happens — with its sub-agents' sessions.
runtime.sessions().subscribe(
        SessionEventFilter.session(sessionId).withChildSessions().withStream(), listener);

// Every session of the namespace, for a monitor.
runtime.sessions().subscribe(SessionEventFilter.all(), listener);
```

Without `withStream()` a listener hears turns begin, ask and end
(`TurnStarted`, `TurnInterrupted`, `TurnFinished`) and what capabilities say
about a session (`Notice`). With it, also every message, every tool call and
what each turn reports as it runs, fragments of the answer included — far
more events, so ask for it only where a session is shown.

A subscription hears what happens from now on. A client that opens while a
turn runs asks `runtime.turns().runningTurn(sessionId)`: the turn that has not
ended, with its status. Over REST a session carries that as `turnId` and
`turnStatus`.

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
