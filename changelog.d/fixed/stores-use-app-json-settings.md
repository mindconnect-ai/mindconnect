- **agents:** **the stores use the application's JSON settings again.** Sessions,
  messages and the other file stores were written with a default `ObjectMapper`
  instead of the application's — timestamps as numbers rather than ISO strings, and
  unknown fields failing a read. `AgentRuntimeBuilder.objectMapper(...)` now reaches
  every feature.
