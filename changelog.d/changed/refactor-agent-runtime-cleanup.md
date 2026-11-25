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
  kinds carry the names the REST frames have (`asking_llm`, `tool_call_started`,
  `approval_requested`, `done`, …) and read in a typed form with `event.known()`: a
  `TurnStreamEvent` for what the agent loop reports and for a question a turn stops for, a
  `SessionStreamEvent` for what the runtime adds, both a `StreamEvent` that names its
  `kind()` and its values. A plugin publishes an event
  of a kind of its own instead of a `Notice`. Gone from the API: `SessionService.lifecycle(…)` and
  `lifecycleOfAll`, `TurnListener` with `onFinished`, `SessionLifecycleListener`,
  `SessionStreamListener`, and the agent loop's `TurnEvent` as what a listener is told.
  `TurnService.subscribe` takes a `SessionEventListener`; a turn's end is its own `done` or `error`.
- **Agents — the session stream of the REST API is the engine's.** `GET /api/sessions/{id}/stream`
  replays what the engine keeps of the session instead of a buffer of its own. The frames are the
  same; their `seq` grows with gaps, because it counts every event of the session and not only the
  ones a client is sent.
- **Agents — the agent loop reports a turn in the words of the stream.** `TurnEvent` is
  `TurnStreamEvent`: `AskingLlm`, `TextDelta`, `Thinking`, `ToolCallStarted`, `ToolCallResult`,
  `ToolCallFailed`, `MessageWritten`, `TurnUsage`. The loop tells of tool calls itself; `TurnEnded`
  and `TurnWaiting` are gone — how a turn left is what `AgentLoop.start` and `resume` return — and an
  event no longer names its request: a listener hears the turn it was given to.
  A question a turn stops for is a `TurnInterruptionEvent` (`ApprovalRequested`, `InputRequested`),
  told by whoever runs the tools. `AskingLlm` is told just before every call of the model: a turn
  that is woken and only goes on waiting no longer says so. On a session's stream a piece of the
  answer is of the kind `text_delta`; the REST frame is still `token`.
