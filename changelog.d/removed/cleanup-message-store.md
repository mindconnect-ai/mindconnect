- The message repository's `MessageType` keeps only `CHAT` and `SYSTEM`: tool
  calls, dispatches, results and approvals are a session's, kept in the
  session's own repository. `ConversationType.USER_AGENT` is gone, since
  sessions are no conversations. Conversations stored with those values before
  are no longer read through the repository; the migrations read them from
  their JSON.
- The runtime no longer provides a conversation store: `StorePlugin` and its
  subclasses drop `MessageRepository`, `ConversationRepository` and
  `ConversationManager`, and the Spring starter their beans. LLM call traces
  are filed and found by session (`findBySession`, `findHeadersBySession`);
  `TraceContext` names session and turn only.
