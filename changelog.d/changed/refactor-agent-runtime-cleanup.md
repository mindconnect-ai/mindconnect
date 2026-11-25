- **Agents — a listener of session and turn events is told on a thread of its own.** The engine hands
  events out through channels: one for the lifecycle of a namespace's sessions and one for the stream
  of each tree of sessions. A slow listener no longer holds up the turn that reports, and a listener
  that throws costs it nothing. `TurnService.subscribe` still tells a listener that comes right after
  `submit` everything from the turn's start; one that comes much later is told what is still kept and,
  in any case, that the turn is over. The events of finished turns are no longer kept for the life of
  the process.
- **Agents — the user stream reads the engine's events.** `GET /api/users/{id}/stream` tells of a user's
  sessions from the engine's own lifecycle channel instead of a second one fed beside it; the frames
  and `afterSeq` are as before. `session_titled` is sent again when a session gets its title.
  `approval_answered` is no longer listed: nothing sent it. `UserStreamPlugin` is gone — an embedding
  that installed it drops the line.
- **Agents — a session has one stream, and its events have one form.** `SessionService.stream` tells all
  of what happens in a session: the answer as it is written, tool calls, a question a turn stops for,
  how a turn ends. An event is a `SessionEvent` with a `kind` and plain `details`; the runtime's own
  kinds carry the names the REST frames have (`token`, `tool_call_started`, `approval_requested`,
  `done`, …) and read in a typed form with `event.known()` (`StreamEvent`). A plugin publishes an event
  of a kind of its own instead of a `Notice`. Gone from the API: `SessionService.lifecycle(…)` and
  `lifecycleOfAll`, `TurnListener` with `onFinished`, `SessionLifecycleListener`,
  `SessionStreamListener`, and the agent loop's `TurnEvent` as what a listener is told.
  `TurnService.subscribe` takes a `SessionEventListener`; a turn's end is its own `done` or `error`.
