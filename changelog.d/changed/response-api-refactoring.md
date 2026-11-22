- **agents:** **the protocol's port interfaces end in `Api`.** In
  `mc-agent-protocol` (`ai.mindconnect.agent.protocol.api`) `AgentResponses` is
  renamed `ResponsesApi`, `Sessions` is `SessionsApi`, `Conversations` is
  `ConversationsApi` and `Files` is `FilesApi`; their methods are unchanged.
  Code that embeds the protocol updates its imports and declared types; the
  backends' accessors (`responses()`, `sessions()`, `conversations()`,
  `files()`) keep their names.
