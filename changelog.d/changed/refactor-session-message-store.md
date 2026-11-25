- `GET /api/sessions/{id}/history` answers with the session's messages as the
  runtime keeps them: each with `sequence`, `sentAt` and the `message` (`type`
  `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_DISPATCHED`, `TOOL_RESULT`; content parts
  with `kind`; tool calls as `toolCalls`). It answered with the message
  repository's format before (`sequenceNum`, `senderType`, `content` as text,
  tool calls as JSON in a string); clients reading the history have to follow.
- The message repository is an area of its own, `conversation/`: modules
  `mc-conversation-core`, `mc-conversation` and `mc-conversation-pg` (were
  `mc-message-repository-core`, `mc-message-repository` and
  `mc-message-repository-pg`), package `ai.mindconnect.conversation` (was
  `ai.mindconnect.message`). The tables and folders it writes stay as they are.
- A conversation is a thread of participants — `PERSON`, `BOT` or `SYSTEM` —
  and their messages: a message has its place, the participant it is `from`,
  content parts and metadata (an agent's message carries `agent.sessionId` and
  `agent.turnId`). `MessageType`, `ConversationType`, turns, compression, token
  counts and durations are gone from it; `ConversationManager` opens, joins,
  posts, receives, closes and deletes, and `ConversationChannel` is the SPI for
  channels such as WhatsApp or Teams. Conversations are kept in
  `mc_conversation_thread`/`mc_conversation_entry` and under
  `conversation-threads/`; the area no longer depends on agent ids.
