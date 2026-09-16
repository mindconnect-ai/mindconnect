| `TaskQueueFeature` | `mc-agent-runtime-feature-taskqueue` | the queue the turns run on, configured: `retention`, `maintenanceInterval`, and `jdbc()` for a store shared across nodes on Postgres | — |---
title: Embedding the runtime
description: Build an agent runtime in plain Java from a core plus installed features — the way an ObjectMapper is a core plus modules.
---

# Embedding the runtime

`mc-agent-runtime-builder` assembles an `AgentRuntime` without Spring. Since 0.9 it
does so the way a Jackson `ObjectMapper` is assembled: a **core** — the turn loop —
plus the **features** you install into it.

```java
try (AgentRuntime runtime = AgentRuntimeBuilder.useFilePersistence(Path.of("./data"))
        .llmConfigFromClasspath("llm/openai.json")
        .agentDefinitionFromClasspath("agents/demo-agent.json")
        .build()) {
    System.out.println(runtime.ask("demo-agent", UserId.of("me"), "Hello?", event -> {}));
}
```

The `use…()` factories are batteries included: they install every feature module
on the classpath — put the `mc-agent-runtime-feature-*` modules you want in your
pom, and they are your runtime's features, each bringing its own capability
modules. Start from `AgentRuntimeBuilder.of(persistence)` instead and the
runtime is the smallest one that chats — the `CoreFeature`: LLM, messages and
agents — plus what you install.

## Features

A feature is one installable capability. It owns its beans, its configuration and
its cross-cutting advisors, and it declares what it depends on.

| Feature | Maven module | Brings | Needs |
|---|---|---|---|
| `CoreFeature` | `mc-agent-runtime-feature-core` | what every runtime has: the LLM layer (config repository, encrypted with a key, gateways, routing chat, embeddings), conversations and messages, agent definitions and everything a session keeps | — (installed by every builder) |
| `SkillsFeature` | `mc-agent-runtime-feature-skills` | stored skills and the catalog | — |
| `ToolsFeature` | `mc-agent-runtime-feature-tools` | the tool registry over the `mc-agent-tools-*` modules on the classpath, dynamic activation, the executor with every contributed `ToolAdvisor` | — |
| `WorkflowsFeature` | `mc-agent-runtime-feature-workflows` | the workflow store the runtime and the workflow tools share — files, memory or Postgres, following the persistence — seeded from the classpath; brings `mc-agent-tools-workflow` | — |
| `FileUploadFeature` | `mc-agent-runtime-feature-file-upload` | the file store (a directory or Postgres, following the persistence) and `attachFile` — indexing a document into the session's vector store; brings `mc-file-store` and `mc-vector-store-tools` | `ToolsFeature` |
| `TranscriptionFeature` | `mc-agent-runtime-feature-transcription` | speech to text by LLM config name, over the OpenAI-compatible transcription endpoint | — |
| `SubAgentsFeature` | `mc-agent-runtime-feature-subagents` | agents delegating to agents: the inline `run_agent`/`run_agents` tools, sub-agent sessions, the recursion depth (`maxDepth`); without it a roster is ignored | `ToolsFeature` |
| `TaskQueueFeature` | `mc-agent-runtime-feature-taskqueue` | the queue the turns run on, configured: `retention`, `maintenanceInterval`, and `jdbc()` for a store shared across nodes on Postgres | — |

The core is always there: `of(persistence)` installs it, because a runtime without
a model, a transcript or a session is not an agent runtime — which is why the
three are one feature. You reach it to configure — `builder.feature(CoreFeature.class)`
before `build()`, `runtime.feature(CoreFeature.class)` after — or replace it by
installing an instance of its class:

```java
AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.inMemory());
builder.feature(CoreFeature.class)
        .llmConfig(LlmConfig.openAi("gpt", apiKey))
        .agentDefinition(myAgent);
AgentRuntime runtime = builder.build();          // chats — no tools, no skills
```

Add tools, and the file upload that needs them:

```java
        .install(new ToolsFeature().disabled("bash"))
        .install(new FileUploadFeature())
```

`installFromClasspath()` installs every feature registered as a service
(`META-INF/services/ai.mindconnect.agent.runtime.feature.RuntimeFeature`) on the
classpath, sorted by their dependencies — the shipped ones and your own.

### Rules

- **Install order is dependency order.** Installing `FileUploadFeature` before
  `ToolsFeature` fails right there, naming both sides. Nothing is installed for
  you behind your back: the list of installed features *is* the runtime's
  configuration.
- **A feature installed under a name already taken replaces it.** That is how a
  configured instance takes the place of a default:
  `useInMemoryPersistence().install(new CoreFeature().encryptionKey(key))`. The
  replacement must be of the same class or a subclass.
- **`configure(Class, Consumer)` reaches an installed feature in place** —
  `.configure(ToolsFeature.class, tools -> tools.disabled("bash"))`. The builder's
  own setters (`llmConfig`, `agentDefinition`, `skill`, `workflowFromClasspath`,
  `encryptionKey`, …) are shorthands for exactly that.
- **After `build()` the wiring is frozen.** Installing then is an error, and so is
  a feature setter: `runtime.feature(CoreFeature.class).encryptionKey(k)` refuses
  with a message rather than changing a field nobody reads any more. The feature
  instance stays reachable — for its settings and its beans — and what changes at
  runtime is data, through the beans: `runtime.llmConfigs().save(config)`.

## Reaching the beans

Everything a feature registers is reachable after `build()`, decorated the way
the core uses it:

```java
runtime.feature(CoreFeature.class).defaultLlmConfigName();  // typed, through the feature
runtime.beans().get(LlmChat.class);                        // generic, by type
runtime.beans().find(FileStore.class);                     // Optional — the feature may be absent
runtime.beans().all(TaskAdvisor.class);                    // the lists features contribute to
runtime.features().has(ToolsFeature.class);
```

The convenience accessors (`runtime.chatService()`, `runtime.llmConfigs()`, …)
are the same lookups. The tools see the same registry as their
`ToolEnvironment`: a bean any feature registers is available to every tool
provider, and a plain-string setting (`builder.property("tavilyApiKey", …)`) is
its `getString`.

## Writing a feature

```java
public class AuditFeature extends ConfigurableFeature {     // or implement RuntimeFeature directly
    private Path logDir;
    public AuditFeature logDir(Path dir) { changing(); this.logDir = dir; return this; }   // refused once built

    @Override public String name() { return "audit"; }
    @Override public Set<Class<? extends RuntimeFeature>> dependsOn() { return Set.of(ToolsFeature.class); }

    @Override protected void install(FeatureContext ctx) {
        ctx.bean(AuditLog.class, () -> new FileAuditLog(ctx.persistence().dataDir()));   // lazy singleton
        ctx.decorate(LlmConfigRepository.class, repo -> new AuditedConfigs(repo, ctx.require(AuditLog.class)));
        ctx.contribute(ToolAdvisor.class, new AuditToolCalls(ctx.require(AuditLog.class)));
        ctx.contribute(TaskAdvisor.class, new AuditTasks());
        ctx.onStart(() -> ctx.require(AuditLog.class).open());
        ctx.onClose(() -> ctx.require(AuditLog.class).close());
    }
}
```

- `ctx.runtime()` is the runtime under construction; its `beans()` and
  `features()` work here as they do after `build()`. Declaring a feature in
  `dependsOn()` guarantees its beans are there when you read them.
- Beans are **lazy singletons**: register suppliers in `configure`, resolve in the
  suppliers and in `onStart`. A feature may decorate a bean a later feature
  provides; decorators apply in registration order, the last one outermost.
- `contribute` is for the many-of-a-kind the core collects: `ToolAdvisor`,
  `TaskAdvisor`, `PromptContextProvider`.
- Repositories come from a **repository factory per domain module** —
  `AgentRepositoryFactory`, `MessageRepositoryFactory`, `LlmRepositoryFactory` —
  with one implementation per backend (`File…`, `InMemory…`, `Pg…`); the file
  store and the workflow store have theirs too (`FileStoreFactory`,
  `WorkflowRepositoryFactory`). A feature looks at the sealed `ctx.persistence()`
  once, to pick the factory, and every repository bean asks its factory; a
  feature of your own does the same (`ctx.require(AgentRepositoryFactory.class).skillRepository()`)
  instead of switching over the backend itself.

Register the class in `META-INF/services/ai.mindconnect.agent.runtime.feature.RuntimeFeature`
and `installFromClasspath()` finds it; or `install(new AuditFeature())` by hand.

## The same runtime in Spring Boot

The server applications build their runtime the same way. `mc-agent-starter-runtime`
is a Spring Boot starter whose auto-configuration runs the builder: the
`Persistence` comes from the persistence starter of the application
(`mc-agent-starter-file` or `mc-agent-starter-postgres`), the thread-bound scope
from the namespace starter — then the `NamespaceFeature` is installed and the
runtime works in the namespace of each request — and the settings are the
`mindconnect.*` properties they always were (`mindconnect.tools.*`,
`mindconnect.agent.*`, `mindconnect.vector-store.*`, …). The starter exports the
runtime's beans to the context, so a controller injects `AgentChatService`,
`LlmConfigRepository`, `ToolRegistry`, … as before, and every bean of the
context is a fallback for the runtime's tools — a tool from an optional module
finds the host's MCP gateway without the runtime naming it.

An application contributes to the runtime with beans:

```java
@Bean RuntimeFeature audit() { return new AuditFeature(); }          // installed, replacing a shipped feature of the same name
@Bean AgentRuntimeCustomizer noBash() { return b -> b.property("disabledTools", "bash"); }
```

and reaches it as `AgentRuntime` — `runtime.feature(CoreFeature.class)`, `runtime.beans()`.
