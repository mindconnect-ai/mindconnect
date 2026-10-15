- **agents:** **user memory is a feature of its own.** The user-memory classes
  left `mc-agent-runtime-core` for `agents/features/user-memory/`:
  `mc-user-memory-core` (package `ai.mindconnect.agent.usermemory`, formerly
  `ai.mindconnect.agent.runtime.usermemory`), `mc-user-memory` and
  `mc-user-memory-pg`. `UserMemoryFeature` installs the store, the service and the
  memory index; `AgentRuntimeBuilder.of(persistence)` no longer brings it, so an
  embedding that starts from `of(…)` installs it itself
  (`install(new UserMemoryFeature())`) — the `use…Persistence()` starting points
  find it on the classpath, and the Spring starter installs it.
  `AgentRepositoryFactory` has no `userMemoryRepository()` any more.
