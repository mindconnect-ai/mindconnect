- **agents:** **parallel sub-agents no longer corrupt the chat's live cards.** Agents
  run with `run_agents` report from several threads at once, and the chat kept its live
  cards in structures only one thread may touch — a card could stay "running" for good,
  or an update fail inside the stream. Events of a turn are now handled one at a time.
  A client that joins a turn after a tool call also no longer sees the thought before
  that call twice.
