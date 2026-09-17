- **agents:** **a turn that waits for an approval can say so.** A tool marked
  `needsApproval` used to leave the caller waiting for the final answer until
  somebody answered the card elsewhere. `AgentRuntime.send(…)` returns as soon
  as a call waits, with a `TurnResult` that is `INCOMPLETE` and lists the open
  questions; `approve(…)` and `deny(…)` continue the same turn and return its
  answer or the next question. Underneath, `ChatTurnHandle.outcome()` completes
  at the question, and `AgentChatService.sendChat`, `approve` and `deny` are the
  non-blocking forms. Over REST the chat stream ends a waiting stretch with an
  `incomplete` frame, and `POST /api/sessions/{id}/approvals/{callId}/continue`
  answers and streams the turn on. The runtime protocol backend ends such a
  response `INCOMPLETE(WAITING_FOR_APPROVAL)` with an `ApprovalRequest` item,
  and an `ApprovalResponse` as the next input continues the turn.
