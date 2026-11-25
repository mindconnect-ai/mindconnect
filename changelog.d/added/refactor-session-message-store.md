- `mindconnect.sessions.messages` chooses where a session's messages are kept:
  `conversation` (the default) keeps them in the message repository as before,
  `own` in a repository of their own — files under `sessions/<id>/messages/`,
  or the Postgres table `mc_session_message`. With `own` the sessions kept so
  far move over on a namespace's first use after start, with their ids,
  places and times; the conversations stay where they are.
