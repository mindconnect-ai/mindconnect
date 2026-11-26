- A session keeps its messages in a repository of its own — files under
  `sessions/<id>/messages/`, or the Postgres table `mc_session_message` — no
  longer as a conversation of the message repository.
- Migrations of stored data: Java classes in `mc-agent-migration`, each named
  with the version it came with, applied once per namespace at its first use
  after start and logged with the data (`mc_migration`, or
  `system/migrations/` on files); `mindconnect.migrations.enabled` switches
  them off. `earlier-runtime` takes over the earlier runtime's agents and
  sessions, messages and summaries included; `session-conversations` moves
  the messages of sessions kept as conversations so far.
