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
