---
id: cancel-mid-tool
area: cancel
requires: [server-9090, lm-studio-tool-model]
duration: ~3 min
last-verified: 2026-08-27 (commit 20667bf, runs/2026-08-27-approval-und-kompression — via automated LM Studio suite)
---

# Cancel mid-tool: stub closes the call, late output is discarded

**Goal:** Cancelling a turn while a tool runs closes the open call with a
synthetic failed TOOL_RESULT ("Cancelled by user"); if the interrupted tool
still produces output afterwards, that output is discarded — never a second
result for the same callId.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)
- The agent has a tool that takes several seconds (a web search against a
  slow site, or code execution with a sleep).

## Steps

1. New session; send a message that triggers the slow tool.
   **Expected:** Running task card appears.
2. While the tool card is still running, click **Stop**.
   **Expected:** The stream ends; the task card flips to failed/aborted — it
   does NOT keep spinning forever.
3. Check the message log immediately.
   **Expected:** Exactly one `TOOL_RESULT` for the call, `failed=true`, text
   `Cancelled by user before the tool finished`. No unpaired `TOOL_CALL`
   remains.
4. Wait longer than the tool would have needed, re-check the log.
   **Expected:** STILL exactly one result for that callId — the tool's late
   output was discarded (server log: `output discarded, the stub owns the
   call`).
5. Send a normal follow-up message in the same session.
   **Expected:** The next turn works normally; the model is not confused by
   the cancelled call (it sees the stub as a failed result).

## Cleanup

- Delete the test session.

## Notes

- The race this protects against is real: the cancel interrupt makes sleeping
  tools return instantly, microseconds around the stub write. The worker
  re-checks the cancel flag after execution — found and fixed via
  `ApprovalLmStudioTest.cancelMidToolWritesAStubAndTheLateResultIsDiscarded`.
