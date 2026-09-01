---
id: memory-auto-compact-eviction
area: memory
requires: [server-9090, lm-studio-tool-model]
duration: ~8 min
last-verified: never
---

# Auto-compact tool-result eviction: old results become fetchable stubs

**Goal:** With `auto_compact` and a `toolResultEviction` policy, tool results
older than `afterTurns` user turns render as pointer stubs in the model's
window (store untouched), and the agent can reload them via
`fetch_tool_result`.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)

## Setup

1. Agent edit page → **Memory (JSON)**:

   ```json
   {
     "kind": "auto_compact",
     "compactAtRatio": 0.8,
     "summaryPlacement": "USER_MESSAGE",
     "toolResultEviction": { "afterTurns": 1, "aboveTokens": 100 }
   }
   ```

2. Enable the `fetch_tool_result` tool on the agent (Tools → Add →
   `fetch_tool_result`).

## Steps

1. New session; turn 1 triggers a sizeable tool result (web search).
   **Expected:** Normal answer using the result.
2. Turn 2: any plain question.
   **Expected:** Normal answer.
3. Open the **Working Memory** view.
   **Expected:** Turn 1's tool result appears as an eviction stub:
   `[Tool result evicted (~N tokens). Call the fetch_tool_result tool with
   this id …]` — while the stored message still holds the full content.
4. Turn 3: ask something that NEEDS the old result's details, e.g.
   `Was stand nochmal genau im Suchergebnis von vorhin? Nutze fetch_tool_result.`
   **Expected:** The agent calls `fetch_tool_result` with the stub's id and
   answers with details from the ORIGINAL result.

## Cleanup

- Restore the agent's previous Memory JSON; remove `fetch_tool_result` if it
  was added only for the test; delete the test session.

## Notes

- Eviction is render-time only — nothing is written to the store (unlike
  summarizing_window's compression marks).
- No keep-newest-three protection here: only the turn boundary
  (`afterTurns`) and the token floor (`aboveTokens`) guard eviction.
