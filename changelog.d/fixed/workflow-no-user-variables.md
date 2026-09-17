- **agents:** **a workflow no longer sees the calling user's own variables.** A
  workflow's `env` held the whole variable chain, including what the user running the
  agent stored for themselves — so a workflow written by one user and called by
  another's agent could read that user's API keys. It now gets the shared view, the
  namespace's and the server's values, like a config's non-secret fields.
