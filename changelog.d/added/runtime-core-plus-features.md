- **agents:** the embedded runtime is now **a core plus installed features**, the way
  a Jackson `ObjectMapper` is a core plus modules. `AgentRuntimeBuilder.of(persistence)`
  is the smallest runtime that chats — the `CoreFeature`: LLM, messages, agents, no tools;
  `install(new ToolsFeature())`, `install(new
  WorkflowsFeature().seed("workflows/x.json"))`, … add what the runtime should have,
  and `installFromClasspath()` picks up every feature registered as a service. A feature declares what it depends on and installing it
  without that dependency is an error, not an auto-install. After `build()` every
  bean any feature registered is reachable — `runtime.beans().get(LlmChat.class)`,
  `runtime.feature(LlmFeature.class)` — and a feature of your own registers beans,
  decorates others' (the way encryption wraps the config store) and contributes
  task and tool advisors. The shipped features live in one Maven module each under
  `agents/builder/mc-agent-runtime-feature-*`. Each domain module gained a
  repository factory per backend (`AgentRepositoryFactory`, `MessageRepositoryFactory`,
  `LlmRepositoryFactory`, `FileStoreFactory`, `WorkflowRepositoryFactory`, with `File…`,
  `InMemory…` and `Pg…` implementations), so a custom assembly picks a factory instead
  of switching per repository. **If you embed the runtime:** the optional features
  are now what you put on the classpath — add `mc-agent-runtime-feature-tools`,
  `-skills`, `-workflows`, `-file-upload` to your pom instead of the capability
  modules they bring along; a builder without them chats without tools. The
  builder's `skill()`, `skillFromClasspath()` and `workflowFromClasspath()` moved to
  `SkillsFeature` and `WorkflowsFeature`. The `use…()` factories still build
  the batteries-included runtime they always did, so nothing changes for a caller
  who does not install anything.
