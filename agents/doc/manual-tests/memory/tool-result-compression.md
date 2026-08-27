---
id: memory-tool-result-compression
area: memory
requires: [server-9091, lm-studio-tool-model]
duration: ~10 min
last-verified: 2026-08-27 (commit 20667bf, runs/2026-08-27-approval-und-kompression — via automated LM Studio suite)
---

# Tool-result compression: old read results shrink, recent ones stay full

**Goal:** With `summarizing_window` and `compressToolResults=true`, tool
results of past rounds are marked compressed at the start of a later turn
execution — never unread results, never the newest three, original preserved
in the store.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)

## Setup

1. Agent edit page → **Memory (JSON)** → set aggressive thresholds so the
   rules (not the sizes) decide:

   ```json
   {
     "kind": "summarizing_window",
     "maxConversationRatio": 0.9, "maxMessageRatio": 1.0,
     "compressToolResults": true,
     "minToolResultCompressTokens": 50,
     "toolResultThresholdRatio": 0.0001,
     "compressWhenWindowAboveRatio": 0.0001,
     "autoSummarize": false, "autoSummarizeRatio": 0.75,
     "summaryPlacement": "SYSTEM_PROMPT", "maxHistoryFetch": 500
   }
   ```

## Steps

1. New session. Run FOUR turns, each triggering one sizeable tool result
   (e.g. `Suche im Web nach <different topic>` four times, waiting for each
   answer).
   **Expected:** Each turn completes; four TOOL_RESULTs exist.
2. Run a FIFTH turn without tools: `Was ist 2+2? Antworte nur mit der Zahl.`
   **Expected:** Normal answer.
3. Open the session's **Working Memory** view (session page → Memory).
   **Expected:** The OLDEST tool result shows as `(compressed)` with a stub
   containing a preview and `call the tool again if you need the full
   output`; the newest THREE results are still full.
4. Check the stored message (message log / conversation JSON).
   **Expected:** The compressed message still has its FULL original in
   `content`; the stub sits in `compressedContent` — nothing was lost.
5. Flip `"compressToolResults": false` in the Memory JSON, run another
   tool turn plus one more plain turn.
   **Expected:** No NEW compressions happen (existing marks remain).

## Cleanup

- Restore the agent's previous Memory JSON (or blank = keep, if you noted the
  original); delete the test session.

## Notes

- Rules under test: (1) unread never compresses, (2) newest three always stay
  full, (3) only under window pressure, (4) only above the size threshold.
  Deterministic twin: `CompressEligibilityTest` (7 cases);
  end-to-end twin: `CompressionLmStudioTest`.
