- **agents:** **the todo list is a feature of its own.** The todo classes left
  `mc-agent-runtime-core` for `agents/features/todo/`: `mc-agent-todo-core` (package
  `ai.mindconnect.agent.todo`, formerly `ai.mindconnect.agent.runtime.tools.todo`),
  `mc-agent-todo` (the file and in-memory stores, now in `…todo.adapter.file` and
  `…todo.adapter.memory`) and `mc-agent-todo-pg` (`…todo.adapter.pg`). `TodoFeature`
  installs the store, the service, the prompt variables and the open-todo hint;
  `AgentRuntimeBuilder.of(persistence)` no longer brings it, so an embedding that
  starts from `of(…)` installs it itself (`install(new TodoFeature())`) — the
  `use…Persistence()` starting points find it on the classpath, and the Spring
  starter installs it. `AgentRepositoryFactory` has no `todoListRepository()` any
  more. `AgentSessionService` takes a `List<SessionCleanup>` in place of the
  `TodoListRepository`: a feature that keeps data per session contributes a
  `SessionCleanup` (`ai.mindconnect.agent.runtime.port.out`) and is told when a
  session is deleted.
