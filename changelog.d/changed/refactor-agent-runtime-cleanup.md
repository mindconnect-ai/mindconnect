- **Agents — a listener of session and turn events is told on a thread of its own.** The engine hands
  events out through channels, one for each tree of sessions — a session with every session under it.
  A slow listener no longer holds up the turn that reports, and a listener
  that throws costs it nothing. `TurnService.subscribe` still tells a listener that comes right after
  `submit` everything from the turn's start; one that comes much later is told what is still kept and,
  in any case, that the turn is over. The events of finished turns are no longer kept for the life of
  the process.
- **Agents — a session has one status, and a list of sessions follows the management.** A session's
  `status` is `IDLE`, `BUSY` or `WAITING`, read from its turn whenever the session is read; the values
  `ACTIVE`, `COMPLETED`, `ERROR` and `CANCELLED`, which nothing set any more, and
  `SessionManagement.changeStatus` are gone. What changes about a user's sessions is told by
  `SessionManagement.lifecycle` as `SessionLifecycleEvent` (opened, renamed, status changed, deleted).
  `GET /api/users/{id}/stream` sends those as `session_started`, `session_titled`, `session_status` and
  `session_deleted`; `turn_started`, `turn_finished`, `approval_requested` and `approval_answered` are
  no longer sent there, and neither are `seq` and `afterSeq`: the stream tells what changes from the
  moment it is attached, and a client that was away reads the list of sessions anew.
  `UserStreamPlugin` is gone — an embedding that installed it drops the line.
- **Agents — a session has one stream, and its events have one form.** `SessionService.stream` tells all
  of what happens in a session: the answer as it is written, tool calls, a question a turn stops for,
  how a turn ends. An event is a `SessionEvent` with a `kind` and plain `details`; the runtime's own
  kinds carry the names the REST frames have (`token`, `tool_call_started`, `approval_requested`,
  `done`, …) and read in a typed form with `event.known()` (`StreamEvent`). A plugin publishes an event
  of a kind of its own instead of a `Notice`. Gone from the API: `SessionService.lifecycle(…)` and
  `lifecycleOfAll`, `TurnListener` with `onFinished`, `SessionLifecycleListener`,
  `SessionStreamListener`, and the agent loop's `TurnEvent` as what a listener is told.
  `TurnService.subscribe` takes a `SessionEventListener`; a turn's end is its own `done` or `error`.
- **Agents — the session stream of the REST API is the engine's.** `GET /api/sessions/{id}/stream`
  replays what the engine keeps of the session instead of a buffer of its own. The frames are the
  same; their `seq` grows with gaps, because it counts every event of the session and not only the
  ones a client is sent.
