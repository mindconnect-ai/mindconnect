- **agents:** `AgentSessionService` no longer takes a `ToolApprovalRepository`: deleting
  a session drops its open approval questions through a `SessionCleanup` the builder
  contributes.
