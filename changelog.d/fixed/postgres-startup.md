- **agents:** **the admin UI and the API server start on Postgres again.** With
  `mindconnect.persistence=postgres` the application stopped at start-up with a
  circular reference between `agentRuntime` and `toolRepository`: the runtime asked
  the application for a tool repository, and the only one there was the runtime
  starter's own export of it. The runtime now looks among the application's beans
  only, never among the ones the starter hands out from the runtime itself.
