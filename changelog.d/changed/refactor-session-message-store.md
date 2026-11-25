- `GET /api/sessions/{id}/history` answers with the session's messages as the
  runtime keeps them: each with `sequence`, `sentAt` and the `message` (`type`
  `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_DISPATCHED`, `TOOL_RESULT`; content parts
  with `kind`; tool calls as `toolCalls`). It answered with the message
  repository's format before (`sequenceNum`, `senderType`, `content` as text,
  tool calls as JSON in a string); clients reading the history have to follow.
