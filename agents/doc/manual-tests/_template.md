---
id: <area>-<short-name>
area: <area>
requires: [server-9091, lm-studio-tool-model]   # environment preconditions, see README
duration: ~X min
last-verified: never
---

# <Short imperative title>

**Goal:** <One sentence: the behaviour this case protects.>

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- <Agent/tool/config prerequisites — name them exactly, and how to set them>

## Setup

1. <Idempotent preparation steps — safe to repeat.>

## Steps

1. <Action — exact URL / button label / message text.>
   **Expected:** <Observable outcome of THIS step.>
2. <Next action.>
   **Expected:** <…>
3. Verify in the message log (or via API):
   **Expected:** <Machine-checkable assertion — exact message type, metadata,
   text. Offer a curl/jq alternative when the UI check is fiddly.>

## Cleanup

- <Undo everything Setup changed — run even after a FAIL.>

## Notes

- <Known flakiness, model dependencies, premise-skip rules.>
