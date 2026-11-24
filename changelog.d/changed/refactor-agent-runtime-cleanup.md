- **Agents — a listener of session and turn events is told on a thread of its own.** The engine hands
  events out through channels: one for the lifecycle of a namespace's sessions and one for the stream
  of each tree of sessions. A slow listener no longer holds up the turn that reports, and a listener
  that throws costs it nothing. `TurnService.subscribe` still tells a listener that comes right after
  `submit` everything from the turn's start; one that comes much later is told what is still kept and,
  in any case, that the turn is over. The events of finished turns are no longer kept for the life of
  the process.
