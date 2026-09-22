- **agents:** **a session has a type, and the chat lists only chats.**
  `AgentSession.type` is a free word — `chat` for a conversation a person
  started in the chat, anything else for a session a feature opens for its own
  purpose (the Office composer's drafting chat is `office`). The chat's history
  and its live list ask for `chat` only: `AgentSessionRepository.findByUser` and
  `findHeadersByUser` take a type, Postgres keeps it in a `session_type` column,
  and `session_started` on the user stream carries `sessionType`, which
  `chat-ui.js` checks before adding a row. `AgentSessionService.openChat(agent,
  user, type)` (and `openChatOfType(agentId, user, type)` for a registry agent) opens one and `sessionsOfType(user, type)` finds them again, so a
  feature can build its own view of its sessions or tidy them up. Sessions
  written before the field — and rows with no `session_type` — are chats.
