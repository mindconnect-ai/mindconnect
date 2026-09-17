- **agents:** the server's scope is **strict**: a thread that touches a store without
  a bound namespace fails instead of silently working in `mindconnect.namespace`.
  Requests bind through the namespace filter, tasks through the runtime; start-up
  routines (the runtime build, the tool warm-up, the seed loaders, the MCP and
  registry seeds) bind the default namespace explicitly. A custom start-up routine
  that reads a repository has to do the same (`scope.runIn(Scope.of(ns), …)`).
