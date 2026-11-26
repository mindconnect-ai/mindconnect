- **embedding:** **the earlier runtime is gone.** The modules
  `mc-agent-runtime`, `mc-agent-runtime-core`, `mc-agent-memory-strategies`,
  the `builder/` modules with their runtime features, `mc-agent-runtime-pg`,
  `mc-agent-starter-runtime`, `mc-agent-starter-runtime-bridge` and
  `mc-agent-protocol-mc-runtime` no longer exist. An application that built
  its runtime with the earlier `AgentRuntimeBuilder` and `RuntimeFeature`s
  builds it with `AgentRuntime.builder()` from `mc-agent-sdk` and plugins; a
  Spring Boot application depends on `mc-agent-starter-runtime-engine`.
- **settings:** **`mindconnect.agent.tool-result.summarizer` is gone.** A tool
  result is no longer summarised by an agent; it is cut at its limit and, once
  old, replaced in the model's context by a note it can be read again from.
- Data only the earlier runtime wrote — working-memory snapshots, conversation
  summaries — is no longer taken over on first access.
- `AuthenticationInfo` (`mc-agent-domain`), which nothing used.
