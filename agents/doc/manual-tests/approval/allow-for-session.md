---
id: approval-allow-for-session
area: approval
requires: [server-9090, lm-studio-tool-model]
duration: ~4 min
last-verified: 2026-08-27 (commit 20667bf, runs/2026-08-27-approval-und-kompression — via automated LM Studio suite)
---

# Allow for this session: no further asking, other sessions still ask

**Goal:** The session-wide approval silences further cards for that tool NAME
in this session (and its sub-agents), while a fresh session asks again.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)

## Setup

1. Set **Needs approval** on `web_search` of the test agent (see
   `approval/deny.md` Setup).

## Steps

1. New session, send: `Suche im Web nach dem Wetter in Hamburg.`
   **Expected:** Approval card appears.
2. Click **Allow for this session**.
   **Expected:** Card disappears; search runs; agent answers.
3. In the SAME session send:
   `Suche im Web nach dem Wetter in Berlin.`
   **Expected:** NO card — the search runs immediately, answer arrives.
4. Open a SECOND, fresh session with the same agent and send a search request.
   **Expected:** The card appears again — the approval was per session, not
   per agent.
5. Click **Deny** in the second session (quick close).

## Cleanup

- Uncheck **Needs approval** on `web_search`; delete both test sessions.

## Notes

- The standing approval is stored per tool NAME on the session record
  (`approvedTools`), inherited downward by sub-agent sessions.
- Automated twin: `ApprovalLmStudioTest.approveForSessionSilencesTheNextCall`.
