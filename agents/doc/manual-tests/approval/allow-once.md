---
id: approval-allow-once
area: approval
requires: [server-9091, lm-studio-tool-model]
duration: ~3 min
last-verified: 2026-08-27 (commit 20667bf, runs/2026-08-27-approval-und-kompression — via automated LM Studio suite)
---

# Allow once: the tool runs, the next call asks again

**Goal:** "Allow once" releases exactly this call; the turn resumes as run 1
of the SAME logical turn, and a later call of the same tool asks again.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)

## Setup

1. Set **Needs approval** on `web_search` of the test agent (see
   `approval/deny.md` Setup).

## Steps

1. New session, send: `Suche im Web nach dem Wetter in Hamburg.`
   **Expected:** Approval card appears; no result yet.
2. Click **Allow once**.
   **Expected:** Card disappears immediately; the search runs (task card with
   result); the agent answers using the search output.
3. Check the message log.
   **Expected:** All messages of this exchange share ONE `turnId`; the
   messages written after the click carry `run=1` (the approval response and
   everything after it), the ones before carry `run=0`.
4. In the SAME session send a second search request:
   `Suche im Web nach dem Wetter in München.`
   **Expected:** A NEW approval card appears — "once" did not stick.
5. Click **Deny** (to end the turn quickly).
   **Expected:** Card disappears, agent answers without a search.

## Cleanup

- Uncheck **Needs approval** on `web_search`; delete the test session.

## Notes

- Automated twin: `ApprovalLmStudioTest.approveOnceRunsTheToolWithinTheSameLogicalTurn`.
