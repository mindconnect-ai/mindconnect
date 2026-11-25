- `mindconnect.sessions.messages` chooses where a session's messages are kept:
  `conversation` (the default) keeps them in the message repository as before,
  `own` in a repository of their own — files under `sessions/<id>/messages/`,
  or the Postgres table `mc_session_message`.
