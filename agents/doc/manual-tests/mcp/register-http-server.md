---
id: mcp-register-http-server
area: mcp
requires: [server-9091, npx]
duration: ~6 min
last-verified: never
---

# Register a streamable-HTTP MCP server, and refuse plain http to a remote host

**Goal:** The second transport works end to end, and the rule that keeps a
pasted token off the wire holds: `http://` is allowed for localhost only.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `npx` on PATH (otherwise: SKIPPED)

## Setup

1. Start a reference MCP server over HTTP in its own terminal:
   `npx -y @modelcontextprotocol/server-everything streamableHttp`
   It listens on **port 3001**. Leave it running for the whole case.
2. Confirm it answers: `curl -s -o /dev/null -w '%{http_code}\n' localhost:3001/mcp`
   **Expected:** a status line, not a connection error.

## Steps

1. http://localhost:9091/mcp-gateway → **Register MCP Server**. Fill in:
   - **Id**: `manualhttp`
   - **Display name**: `Manual Test Everything`
   - **Tool name prefix**: `mev`
   - **Target (JSON)**:
     ```json
     { "type": "http", "url": "http://localhost:3001/mcp", "headers": {} }
     ```
   **Expected:** The form accepts the values.
2. Click **Test connection**.
   **Expected:** Result below the form within ~2 s, listing the server's tools
   (`echo`, `add`, `printEnv`, … — 10 or more), with a duration in ms. The
   form still holds everything typed.
3. Click **Save**, then open http://localhost:9091/admin/tools and search `mev`.
   **Expected:** Section **Manual Test Everything** under group **Mcp**, with
   `mev_echo` among the tools.
4. Open `mev_echo` and click **Test**. Enter arguments
   `{"message": "manual test"}` and run.
   **Expected:** The result contains `manual test`. Round trip well under a
   second — the connection is pooled, not a fresh process per call.
5. Now the security rule. Go back to
   http://localhost:9091/mcp-gateway → row `Manual Test Everything`, and change
   **Target (JSON)** to a remote host over plain http:
   ```json
   { "type": "http", "url": "http://mcp.example.com/mcp", "headers": {} }
   ```
   Click **Save**.
   **Expected:** The save is **refused** with a readable message naming the
   rule — "url must be https (http is allowed for localhost only)". The form
   is still on screen **with the entered JSON**, not emptied and not a 500.
6. Change the url to `https://mcp.example.com/mcp` and click **Test connection**.
   **Expected:** The form is accepted (the scheme is legal) and the probe
   **fails** with a connection/DNS error naming the host. That is the correct
   outcome: the rule is about the scheme, not about the host existing.

## Cleanup

- http://localhost:9091/mcp-gateway → `Manual Test Everything` → **Delete**.
- Stop the `server-everything` process (Ctrl-C in its terminal).

## Notes

- `127.0.0.1`, `::1` and `*.localhost` count as local too; everything else
  needs https. The reason is in `McpTarget.Http`: headers hold pasted tokens
  and there is no credential store yet, so an unencrypted hop would put them
  on the wire in clear.
- Step 5 also covers the form-state regression: a rejected save re-renders the
  draft rather than a domain object, so nothing typed is lost.
- Automated twin for the scheme rule and the JSON round trip:
  `McpTargetFormTest`, `McpServerDraftTest`.
