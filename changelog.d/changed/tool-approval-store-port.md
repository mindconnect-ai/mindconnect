- **agents:** **open approval questions are stored behind a port.** `ToolApprovalStore`
  is now the interface `ToolApprovalRepository` (`ai.mindconnect.agent.runtime.port.out`)
  with `InMemoryToolApprovalRepository` in `mc-agent-runtime` as the default; a runtime
  feature that registers its own `ToolApprovalRepository` replaces it. The runtime
  accessor `AgentRuntime.approvalStore()` is now `toolApprovals()`. Storage is still
  in memory only, so an open question does not survive a restart.
