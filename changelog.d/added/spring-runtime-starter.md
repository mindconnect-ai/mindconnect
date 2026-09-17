- **agents:** **`mc-agent-starter-runtime`** — the Spring Boot applications now build
  their agent runtime the way the embedded builder does: the core plus installed
  features, configured from the `mindconnect.*` properties, with the runtime's beans
  exported to the context. The persistence starters supply a `Persistence`
  (and the installation-wide users and API tokens), the namespace starter the
  thread-bound scope; `DefaultAgentRuntimeConfig`, `TodoToolsConfig` and
  `MessageRepositoryConfig` are gone, and so are the LLM gateway beans the admin
  and API apps declared themselves. An application contributes `RuntimeFeature`
  beans (installed, replacing a shipped feature of the same name) and
  `AgentRuntimeCustomizer` beans, and injects `AgentRuntime` to reach any bean or
  feature. `mc-agent-runtime-feature-namespace` is the feature behind it: a runtime
  that works in the namespace of the call, for any host that binds a scope;
  `mc-agent-runtime-feature-transcription` is speech to text as a feature of its
  own, installed by the starter, left out by a runtime that never hears audio. Every
  domain module's repository factory can build for a namespace, and the tool
  environment falls back to the host's beans.
