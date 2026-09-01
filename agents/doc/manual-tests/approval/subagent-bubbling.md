---
id: approval-subagent-bubbling
area: approval
requires: [server-9090, lm-studio-tool-model]
duration: ~8 min
last-verified: never
---

# Sub-agent approval: the card bubbles up, the answer routes down

**Goal:** An approval needed INSIDE a delegated sub-agent surfaces as a card
in the ROOT chat (via the ToolApprovalStore, no message copies), the answer
resumes the sub-turn, and the root turn continues on its ORIGINAL stream.

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
   delegation, e.g. `Recherchiere das Wetter in Hamburg.`
   **Expected:** A sub-agent task card appears; shortly after, an
   **Approval required** card for `web_search` appears in the ROOT chat while
   the sub-agent card stays running.
2. Reload the page (F5) before answering.
   **Expected:** The card is STILL there (rendered from the ToolApprovalStore,
   not from the live stream) and the in-flight task cards survived the reload.
3. Check the ROOT conversation's message log.
   **Expected:** NO `APPROVAL_REQUEST` message in the root conversation — the
   request lives only in the sub-agent's conversation; the root card comes
   from the store.
4. Click **Allow once**.
   **Expected:** Card disappears immediately; NO second stream starts; the
   sub-agent resumes, finishes its search, and the ROOT turn completes with a
   final answer on the original stream.
5. Check the SUB-agent session (root session page → sub-agent card → open
   session).
   **Expected:** `APPROVAL_REQUEST` + `APPROVAL_RESPONSE` paired by callId,
   the tool ran, one `TOOL_RESULT`; sub-turn messages share one turnId with
   `run` 0→1.

## Cleanup

- Uncheck **Needs approval** on the sub-agent's `web_search`; delete the test
  session (sub-sessions go with it).

## Notes

- Cancel variant worth spot-checking: cancel the ROOT chat while the card is
  open → the card must disappear (store cleanup on cancel).
- "Allow for this session" here stores the approval on the ROOT session and
  covers all sub-agents of the conversation (inheritance walks the parent
  chain).
