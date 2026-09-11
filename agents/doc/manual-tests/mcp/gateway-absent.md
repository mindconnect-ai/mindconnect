---
id: mcp-gateway-absent
area: mcp
requires: [server-restartable]
duration: ~5 min
last-verified: never
---

# With MCP switched off, the app has no MCP anything

**Goal:** `mindconnect.mcp.enabled=false` removes the gateway, its screen, its
entry points — and nothing else. No dead nav entry, no button to a route
nobody serves, and agents that use no MCP tools keep working.

## Preconditions

- The admin UI can be restarted with a changed property (otherwise: SKIPPED)
- Note: this case **restarts the server**. Run it last in a suite.

## Setup

1. Note whether any MCP servers are registered
   (http://localhost:9091/mcp-gateway) — they are only hidden here, not
   deleted, and they come back in Cleanup.

## Steps

1. Stop the server and start it again with the property set:
   `./agents/server/mc-agent-admin-ui-app/start.sh -Dspring-boot.run.arguments="--mindconnect.mcp.enabled=false"`
   **Expected:** The application starts. No error about missing MCP beans.
2. Open http://localhost:9091/admin/agents and look at the sidebar.
   **Expected:** **No** entry **MCP Servers**. Chat, Agents, Tools, LLM
   Configs, Workflows, Vector Stores, Migrations, API are all still there.
3. Open http://localhost:9091/admin/tools.
   **Expected:** The catalog renders with the built-in tools. **No**
   **Register MCP Server** button in its header — there is no gateway to
   register with. No `mfs_*` or other MCP tools.
4. Navigate directly to http://localhost:9091/mcp-gateway.
   **Expected:** Not found. This is the correct outcome — and step 2 is the
   point: the sidebar no longer offers it.
5. Open a chat with any agent that uses **built-in** tools only and use one.
   **Expected:** Works normally. Switching MCP off costs MCP, nothing else.
6. Restart without the property (plain `start.sh`).
   **Expected:** **MCP Servers** is back in the sidebar,
   **Register MCP Server** is back in the tool catalog's header, and the
   registrations from Setup are all there — nothing was deleted.

## Cleanup

- Step 6 is the cleanup: the server must end up running without the property.

## Notes

- This is the regression for a nav entry that was rendered unconditionally
  while its controller was conditional — the sidebar offered "MCP Servers" and
  the click gave a 404. Automated twin: `AdminLayoutMcpNavTest`.
- The catalog of *suggestions* has its own switch, `MC_MCP_CATALOG_ENABLED`,
  and is **off by default** for a different reason: it fetches from a third
  party, and an installation that wants no outbound call should not make one
  because somebody opened a screen. Registering by hand works without it.
- An agent that *does* bind MCP tools loses them here — it keeps working minus
  those capabilities, which is the same behaviour as a disabled tool.
