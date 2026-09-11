---
id: mcp-register-stdio-server
area: mcp
requires: [server-9091, npx, lm-studio-tool-model]
duration: ~8 min
last-verified: never
---

# Register a stdio MCP server and let an agent call it

**Goal:** The walking skeleton. A server registered through the UI reaches the
tool catalog under its own prefix, and an agent bound to one of its tools
actually calls it.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `npx` on PATH; the machine may reach the npm registry once (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)

## Setup

1. Create a directory with one readable file:
   `mkdir -p /tmp/mcp-manual && echo 'the manual test marker is 4711' > /tmp/mcp-manual/note.txt`

## Steps

1. Open http://localhost:9091/mcp-gateway.
   **Expected:** Page **MCP Servers** with a **Register MCP Server** button.
   No error banner.
2. Click **Register MCP Server**. Fill in:
   - **Id**: `manualfs`
   - **Display name**: `Manual Test Filesystem`
   - **Tool name prefix**: `mfs`
   - **Target (JSON)**:
     ```json
     {
       "type": "process",
       "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp/mcp-manual"],
       "env": {}
     }
     ```
   **Expected:** All five fields hold what was typed; **Enabled** is on.
3. Click **Test connection**.
   **Expected:** Within ~30 s a result appears **below the form**, listing the
   server's tools (`read_text_file`, `list_directory`, … — 10 or more).
   Everything typed in step 2 is **still in the form**. The page did not
   navigate away.
4. Click **Save**.
   **Expected:** Back on **MCP Servers**; a row `Manual Test Filesystem` whose
   line names the transport, the whole command, the tool count and the prefix:
   `process npx -y @modelcontextprotocol/server-filesystem /tmp/mcp-manual · N tool(s) · prefix mfs_`
5. Open http://localhost:9091/admin/tools and search for `mfs`.
   **Expected:** Group **Mcp** contains a collapsible section
   **Manual Test Filesystem  (N)** holding `mfs_read_text_file`,
   `mfs_list_directory` and the rest. Each tool has a description and a
   parameters table — not an empty row.
   Alternatively: `curl -s localhost:9091/admin/api/tools | grep -c mfs_`
6. Create an agent at http://localhost:9091/admin/agents (**New agent**), name
   `MCP Manual`, and add the tools `mfs_list_directory` and
   `mfs_read_text_file`. Save.
   **Expected:** Both appear in the tool dropdown under `Mcp · mfs_…` and the
   saved agent lists them.
7. Open a chat with `MCP Manual` and send:
   `Liste /tmp/mcp-manual auf und lies die Datei note.txt vor.`
   **Expected:** The agent calls `mfs_list_directory` and then
   `mfs_read_text_file`, and its answer contains **4711**.

## Cleanup

- http://localhost:9091/mcp-gateway → row `Manual Test Filesystem` → **Delete**.
- Delete the agent `MCP Manual` and its sessions.
- `rm -rf /tmp/mcp-manual`

## Notes

- Observed counts at the time of writing, as a sanity anchor:
  `@modelcontextprotocol/server-filesystem` offers **14** tools,
  `@modelcontextprotocol/server-everything` **13**. A different number is not a
  FAIL by itself — the packages move — but zero is.
- The first **Test connection** pays for the npx download and may take ~30 s;
  every later one is fast because the discovered tools are cached on disk under
  `data/<namespace>/system/mcp-schema-cache/manualfs.json`.
- Tool names compose as `<toolNamePrefix>_<subTool>`. If step 5 shows names
  like `mfs_mfs_…`, the server already prefixes its own tools — that is a
  prefix choice, not a defect (see `mcp/catalog-takeover.md`).
- Automated twin for the naming and namespace rules:
  `McpMultiToolProviderTest`, `SpiToolRegistryScopeTest`.
