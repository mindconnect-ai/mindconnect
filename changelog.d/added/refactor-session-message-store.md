- `mindconnect.sessions.messages` chooses where a session's messages are kept:
  `own` (the default) keeps them in a repository of their own — files under
  `sessions/<id>/messages/`, or the Postgres table `mc_session_message`;
  `conversation` keeps them in the message repository as before. With `own`
  the sessions kept so far move over on a namespace's first use after start,
  with their ids, places and times; the conversations stay where they are.
- Migrations of stored data: Java classes in `mc-agent-migration`, each named
  with the version it came with, applied once per namespace at its first use
  after start and logged with the data (`mc_migration`, or
  `system/migrations/` on files); `mindconnect.migrations.enabled` switches
  them off. The first one takes over the earlier runtime's agents and sessions,
  messages and summaries included.
