- **agents:** `ToolApproval` moved from `ai.mindconnect.agent.runtime.service.approval`
  to `ai.mindconnect.agent.runtime.domain`, next to the new `TurnResult`. Code that
  reads open approvals needs the new import; the JSON is unchanged.
