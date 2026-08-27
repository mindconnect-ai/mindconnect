---
id: approval-deny
area: approval
requires: [server-9091, lm-studio-tool-model]
duration: ~3 min
last-verified: 2026-08-27 (commit 20667bf, runs/2026-08-27-approval-und-kompression — via automated LM Studio suite)
---

# Deny: a denied tool never runs, the model still answers

**Goal:** Denying an approval card produces a failed TOOL_RESULT ("did not
approve") without ever executing the tool, and the turn still ends with an
assistant answer.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded
  (`GET http://localhost:1234/api/v0/models` shows `"state": "loaded"` and
  `"tool_use"` — otherwise: SKIPPED)

## Setup

1. Open http://localhost:9091/admin/agents → an agent with a web tool (e.g.
   `assistant-with-tools`) → Tools → `web_search` → Edit → check
   **Needs approval** → Save.

## Steps

1. Open a new session with that agent and send:
   `Suche im Web nach dem Wetter in Hamburg.`
   **Expected:** An **Approval required** card appears showing `web_search`
   and the concrete arguments; three buttons (Deny / Allow once / Allow for
   this session); NO search runs, NO final answer yet.
2. Click **Deny**.
   **Expected:** The card disappears immediately; the agent produces an
   answer that acknowledges it could not search.
3. Open the session's message log (session page → Traces, or the conversation
   JSON under the data directory).
   **Expected:** Exactly one `TOOL_RESULT` for the call, `failed=true`, text
   contains `did not approve`. No tool was executed (no search output
   anywhere in the conversation).

## Cleanup

- Uncheck **Needs approval** on `web_search` again; delete the test session.

## Notes

- Automated twin: `ApprovalLmStudioTest.denyLeavesTheToolUnrunAndTheModelAnswers`.
