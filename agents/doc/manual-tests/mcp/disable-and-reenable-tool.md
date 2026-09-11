---
id: mcp-disable-and-reenable-tool
area: mcp
requires: [server-9091]
duration: ~4 min
last-verified: never
---

# Switching a tool off is not a one-way door

**Goal:** A disabled tool disappears from agents but keeps its row in the
catalog, so it can be switched back on from the UI.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- Any registered MCP server, or just use the built-in `glob` — the mechanism is
  the same for both, and that is the point of the tool repository

## Steps

1. http://localhost:9091/admin/tools, search `glob`.
   **Expected:** A row `glob — Finds files by name pattern …`.
2. Open the row → **Settings** → switch **Available to agents** off → **Save**.
   **Expected:** Message "Saved."
3. Close the dialog and reload http://localhost:9091/admin/tools, search `glob`
   again.
   **Expected:** **The row is still there**, and its summary reads
   `glob  (off) — Finds files by name pattern …`. Its description is still
   shown — a disabled tool is displayed as its source defines it.
   Machine check: `curl -s localhost:9091/admin/api/tools | grep -c 'glob  (off)'`
   → `1`
4. Open the row → **Settings**.
   **Expected:** The dialog opens, and **Available to agents** is **off**.
5. Open any agent that binds `glob` (or create one) and start a session; ask it
   to use the tool.
   **Expected:** The agent does not offer or call `glob`. It answers without
   it and does not error out — a disabled tool is a missing capability, not a
   failure.
6. Back in **Settings**, switch **Available to agents** on → **Save** → reload
   the catalog.
   **Expected:** The `(off)` marker is gone; the row is a normal row again;
   the agent from step 5 can use the tool again in a **new** session.

## Cleanup

- Make sure step 6 ran, so `glob` is enabled again.
- `cat data/*/system/tool-settings.json` should hold no `glob` entry.

## Notes

- This is the regression for a defect where the catalog listed only *enabled*
  tools. Because the Settings dialog hangs off a catalog row, switching a tool
  off removed the only way to switch it back on — the store had to be edited by
  hand.
- The operator's switch is policy: an agent definition cannot bring a disabled
  tool back. Automated twin:
  `OverlayToolRegistryTest.an_agent_cannot_bring_a_disabled_tool_back`.
- Step 5 needs a **new** session if the agent already had the tool resolved.
