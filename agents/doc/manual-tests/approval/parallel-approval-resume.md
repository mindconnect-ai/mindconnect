---
id: approval-parallel-resume
area: approval
requires: [server-9090, lm-studio-parallel-toolcall-model]
duration: ~5 min
last-verified: 2026-09-16 (commit f86ac52e, runs/2026-09-16-full-suite)
---

# Answering a card while another tool still runs: no second execution

**Goal:** When the model calls a slow tool AND an approval-gated tool in ONE
response, answering the card while the slow tool runs must NOT fork a second
turn execution — the live task is woken instead, and every call gets exactly
one result.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio with a model that emits MULTIPLE tool calls in one response.
  **Premise-skip rule:** if after 3 attempts the model always calls the tools
  sequentially, record SKIPPED ("model serialises tool calls") — gpt-oss via
  LM Studio is known to do this.

## Setup

1. Test agent with TWO tools: one slow (e.g. a long-running search / code
   execution) WITHOUT approval, and one WITH **Needs approval**. A setup that
   works: a new agent with `bash` (no approval) and `get_current_datetime`
   (**Needs approval**), and the message
   `Call BOTH tools in ONE single response, in parallel: (1) bash with command "sleep 45; echo SLOW-DONE" and (2) get_current_datetime with no arguments. Then tell me both outputs.`

## Steps

1. New session; instruct the model explicitly to call BOTH tools in one
   response (name both tools and arguments in the message).
   **Expected:** One TOOL_CALL message containing both calls; the slow tool
   starts (running task card), the approval card appears WHILE it runs.
2. While the slow tool is still running, click **Allow once**.
   **Expected:** Card disappears; NO new/second answer stream starts; the
   original turn keeps running.
3. Wait for the turn to finish.
   **Expected:** ONE final answer mentioning both tool outputs.
4. Check the message log.
   **Expected:** Exactly ONE `TOOL_RESULT` per callId (no duplicates); all
   messages carry `run=0` — no `_r1` resume execution was started. Server log
   shows `Approval answer for call <callId> (granted, scope ONCE) delivered to task task_tool_<turnId>_<callId>`,
   and only ONE `POST /chat/api/sessions/<id>/chat/stream` for the session.

## Cleanup

- Reset the approval flag; delete the test session.

## Notes

- The guard itself (alive task → notify instead of fork; terminal task →
  regular resume with run+1) is covered deterministically by
  `PrepareApprovalGuardTest` against a real LocalTaskQueue — this manual case
  additionally proves the end-to-end wiring when a capable model is loaded.
- Automated twin (skips on serialising models):
  `ApprovalLmStudioTest.answeringWhileAnotherToolStillRunsWakesTheLiveTaskInsteadOfForking`.
