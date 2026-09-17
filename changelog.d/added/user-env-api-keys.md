- **agents:** **bring your own API key** — a `${VAR}` placeholder in an LLM config
  no longer has to come from the server's environment. On the profile page every
  user keeps *Your variables* (an `OPENAI_API_KEY` of their own, say), the creator
  of a namespace keeps *Variables* for everyone working there, and a placeholder
  takes the user's value first, then the namespace's, then the process
  environment; values are encrypted at rest and never shown again. What a user
  brought for themselves reaches a config's **`apiKey` only** — its `model`,
  `baseUrl` and `name` resolve without anybody's personal variables, so nobody
  redirects a shared config's endpoint to a host of their own while the
  installation's key still travels with it. A namespace's variables are its
  creator's to set, like renaming and deleting it; the **default namespace has
  none of its own** — nobody created it and everyone works there — so a `${VAR}`
  there means your own value, else the server's environment. The lookup itself
  is a port now (`EnvVarResolver` in `mc-common`, with `system()`, `of(map)`,
  `chain(…)`, `shared()` and `memoized()`; the static `EnvVarResolver.resolve(value)`
  became `resolver.resolve(value)`): the gateways take one,
  `AgentRuntimeBuilder.envVarResolver(…)` plugs a source of your own into an
  embedded runtime, a runtime feature reads it with
  `ctx.require(EnvVarResolver.class)`, and a Spring host may define its own
  `EnvVarResolver` bean. Model listing and the workflow engine's `env` variable
  (a run from the admin, a workflow tool in a chat, a vector-store ingestion)
  resolve through the same chain; the migration diff stays on the process
  environment, so it reads the same for whoever opens it.
