---
id: reviewer-response-chain
area: reviewer
requires: [server-9091, lm-studio-tool-model]
duration: ~8 min
last-verified: never (chain logic verified by ResponseReviewerChainTest + AgentLoopTest, 2026-08-27)
---

# Response reviewers: rewrite and BLOCK before the user sees anything

**Goal:** An agent's answer passes through its configured reviewer agents in
order; a rewrite replaces the text BEFORE it is persisted (the conversation
never holds the draft), and a `BLOCK:` verdict replaces the answer and stops
the chain.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)

## Setup

1. Create a reviewer agent `tone-reviewer` (Agents → New):
   - System prompt: `You review another agent's answer. The user question is
     {{ user_message }}, the draft answer is {{ agent_response }}. If the
     draft contains the word "BANANA", reply exactly: BLOCK: Answer withheld
     by reviewer. Otherwise reply with the draft unchanged but append the
     line "— reviewed". Never explain yourself.`
2. On the agent under test (e.g. `assistant-with-tools`): Edit → Response
   Reviewers → select `tone-reviewer` → Save.

## Steps

1. New session; send: `Antworte exakt mit: Hallo Welt`
   **Expected:** During the turn a "reviewing…" indicator appears; the final
   answer ends with `— reviewed`.
2. Check the message log.
   **Expected:** Exactly ONE agent CHAT message, already containing
   `— reviewed` — the unreviewed draft exists nowhere.
3. Send: `Antworte exakt mit: BANANA`
   **Expected:** The visible answer is `Answer withheld by reviewer` (the
   text after `BLOCK:`) — not the draft.
4. Remove the reviewer from the agent, send any message.
   **Expected:** Answers come through unmodified again.

## Cleanup

- Remove `tone-reviewer` from the agent's Response Reviewers; delete the
  reviewer agent and the test session.

## Notes

- PASS conventions (`PASS`, `pass - ok`, `` `PASS` ``), ordering, fail-open
  on reviewer crashes and the last_messages view are covered
  deterministically by `ResponseReviewerChainTest`; the loop seam (persisted
  = reviewed) by `AgentLoopTest.theRealReviewerAdvisorRewritesTheAnswerThroughTheChain`.
- Found & fixed 2026-08-27: a reviewer answering `` `PASS` `` (trailing
  backtick) used to REPLACE the answer with the literal text.
