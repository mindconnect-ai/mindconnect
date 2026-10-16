- **agents:** **the approval gate is a port.** `ToolCallWorker` and `AgentChatService` ask an
  `ApprovalGate` (`ai.mindconnect.agent.runtime.service.approval`) instead of the
  `ToolApprovalRepository`: `check` right before a tool runs, `open`, `answer` and `close`.
  The gate that parks a call's task until the answer arrives is `ParkingApprovalGate` in the
  new module `mc-agent-approvals` (`ai.mindconnect.agent.approval`), with
  `ApprovalNotifications` (formerly `ai.mindconnect.agent.runtime.service.approval`); the
  builder installs it unless a feature registers its own `ApprovalGate`. `ToolCallWorker`
  takes the gate in place of the store and the user channels; `AgentChatService` takes it
  in place of the store.
