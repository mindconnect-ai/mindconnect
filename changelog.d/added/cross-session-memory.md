- **agents:** **agents remember the user across chats.** Three new tools —
  `memory_write`, `memory_read`, `memory_delete` — keep small entries per
  user and namespace: who the user is, how they want things done, ongoing
  projects, where to find things. An agent with a memory tool sees the list
  of entries in its system prompt every round and reads an entry in full on
  demand; `memory_write` makes the memory read-write, `memory_read` alone
  read-only, and an agent without either sees nothing of it. New
  installations get all three on `default-chat`; on an existing one, add
  them to the agent in the admin UI. Stored in `mc_user_memory` with
  Postgres persistence, as Markdown files under
  `<data>/<namespace>/memory/<user>/` with file persistence. Features can
  now add sections to every system prompt through `PromptSection`.
