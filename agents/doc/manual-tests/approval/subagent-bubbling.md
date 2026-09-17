---
id: approval-subagent-bubbling
area: approval
requires: [server-9090, lm-studio-tool-model]
duration: ~8 min
last-verified: 2026-09-16 (commit f86ac52e, runs/2026-09-16-full-suite)
---

# Sub-agent approval: the card bubbles up, the answer routes down

**Goal:** An approval needed INSIDE a delegated sub-agent surfaces as a card
in the ROOT chat (via the ToolApprovalStore, no messages), the answer wakes
the sub-agent's parked tool call inside its still-running turn, and the root
turn continues on its ORIGINAL stream.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)
- A delegating agent (e.g. `research-lead` with `run_agent`) whose SUB-agent
  (e.g. `web-researcher`) has a tool available.

## Setup

1. Set **Needs approval** on `web_search` of the SUB-agent
   (`web-researcher`), NOT on the root agent.

## Steps

1. New session with the ROOT agent (`research-lead`); send a task that forces
   delegation, e.g.
   `Recherchiere das Wetter in Hamburg. Beauftrage dafür genau EINEN web-researcher, keine Verifikation, kein weiterer Sub-Agent.`
   (the limit keeps it to one card — `research-lead` otherwise fans out).
   **Expected:** A sub-agent task card (`web-researcher — running…`) appears;
   shortly after, an approval card `The agent wants to run web_search.` appears
   in the ROOT chat while the sub-agent card stays running.
2. Reload the page (F5) before answering.
   **Expected:** The card is STILL there (rendered from the ToolApprovalStore,
   not from the live stream) and the in-flight task cards survived the reload.
3. Check the ROOT conversation's message log.
   **Expected:** NO `APPROVAL_*` message in the root conversation; the root
   card comes from the store — `GET /api/sessions/<root>/approvals` lists it
   with `originSessionId` = the sub-agent session and `rootSessionId` = the
   root session.
4. Click **Allow once**.
   **Expected:** Card disappears immediately; NO second stream starts (server
   log: `Approval answer for call <callId> (granted, scope ONCE) delivered to task …`,
   and a single `POST …/chat/stream` for the root session); the sub-agent's
   search runs, and the ROOT turn completes with a final answer on the
   original stream.
5. Check the SUB-agent session (`GET /api/sessions/<originSessionId>/history`).
   **Expected:** No `APPROVAL_*` messages there either — the approval leaves
   no message; `web_search`'s `TOOL_RESULT` (`failed=false`) follows its
   `TOOL_CALL` once; all sub-turn messages share one turnId and carry `run=0`
   (the gate parks the call, no resume run).

## Cleanup

- Uncheck **Needs approval** on the sub-agent's `web_search`; delete the test
  session (sub-sessions go with it).

## Notes

- Cancel variant worth spot-checking: cancel the ROOT chat while the card is
  open → the card must disappear (store cleanup on cancel).
- "Allow for this session" here stores the approval on the ROOT session and
  covers all sub-agents of the conversation (inheritance walks the parent
  chain).
