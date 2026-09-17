- **agents:** two more features. **`SubAgentsFeature`** (`mc-agent-runtime-feature-subagents`)
  is delegation: an agent's roster yields `run_agent`/`run_agents` only with it
  installed, and `maxDepth` bounds the chain (`mindconnect.agent.sub-agents.max-depth`
  in Spring, default 5). **`TaskQueueFeature`** (`mc-agent-runtime-feature-taskqueue`)
  configures the queue the turns run on — `retention`, `maintenanceInterval`, and
  `jdbc()` for a `JdbcTaskStore` shared across nodes on Postgres; in Spring
  `mindconnect.task-queue.{retention,maintenance-interval,store,node-id,lease}`,
  with the store in memory and finished tasks kept, as before.
