- **agents:** **users see and delete what agents remember about them, and an
  agent can keep a memory of its own.** The profile has a new **Memory** tab
  listing the signed-in user's entries — the memory their agents share and
  each agent's own — with the entry in full and a delete per row; admins get
  **Data → Memory** (`/admin/memories`) with every user's entries in the
  namespace, filterable by user. `GET /api/memories`, `GET` and `DELETE
  /api/memories/{name}` (`?agent=` for an agent's own) read and delete the
  caller's own entries; the route is open to every member of the namespace.
  A memory tool's binding takes the setting `scope`: `user` (the default, as
  before) for the shared memory, `agent` for what only this agent keeps about
  the user — a secretary's, say — or `both`, where the model says with each
  write which one. Agent entries live under `<user>/agents/<agentId>/` (files)
  or with the key `<user>/@<agentId>/<name>` (Postgres); existing entries stay
  shared.
