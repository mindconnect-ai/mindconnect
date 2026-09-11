---
id: mcp-variables-are-refused
area: mcp
requires: [server-9091, server-restartable]
duration: ~5 min
last-verified: never
---

# A variable in a registration is refused; the process environment is never read

**Goal:** A `${NAME}` placeholder in `env` or `headers` stays storable, but a
server that uses one does not start — the failure names the field and the
variable, never the value. Variables will resolve only from the signed-in
user's own variables, which do not exist yet. The environment of the admin UI
process is never a source, even when the variable is set there. A value
without a placeholder is used as entered.

## Preconditions

- The admin UI can be restarted with an extra environment variable
  (otherwise: SKIPPED) — the case proves that a variable set for the process
  is still not read.

## Setup

1. Stop the admin UI and start it again with a test variable set:
   `MC_TEST_SECRET=s3cret-from-outside ./agents/server/mc-agent-admin-ui-app/start.sh`

## Steps

1. http://localhost:9091/mcp-gateway → **Register MCP Server**:
   - **Id**: `envtest`
   - **Display name**: `Env Test`
   - **Tool name prefix**: `envt`
   - **Target (JSON)**:
     ```json
     {
       "type": "process",
       "command": ["/bin/sh", "-c", "echo \"seen: $TOKEN\" >&2; exec /bin/cat"],
       "env": { "TOKEN": "${MC_TEST_SECRET}" }
     }
     ```
   **Expected:** The hint under **Target (JSON)** says values are stored and
   sent as entered, and that `${NAME}` is reserved for variables of the
   signed-in user.
2. Click **Save**, then open the registration file:
   `cat data/*/system/mcp-servers/envtest.json`
   **Expected:** `"TOKEN": "${MC_TEST_SECRET}"` — the placeholder is stored.
3. Click **Test connection**.
   **Expected:** Failure within milliseconds, before anything is started:
   `environment variable 'TOKEN' uses the variable ${MC_TEST_SECRET}, but
   variables resolve only from a user's own variables, and there are none yet —
   enter the value itself`. The server log has **no** `seen:` line, and
   `grep -r s3cret-from-outside data/ logs/ ; echo "exit=$?"` → `exit=1`.
4. Change the value to a variable with a default:
   `"TOKEN": "${MC_DOES_NOT_EXIST:fallback}"`. Click **Test connection**.
   **Expected:** Refused the same way — a default does not make a variable
   acceptable; no `seen: fallback` in the log.
5. Change the value to a literal: `"TOKEN": "literal-value"`. Click
   **Test connection**.
   **Expected:** The probe starts the process and fails on the handshake
   (`/bin/cat` speaks no MCP); the log shows `seen: literal-value`.
6. Register a second server with an http target and a variable in a header:
   ```json
   { "type": "http", "url": "https://mcp.deepwiki.com/mcp",
     "headers": { "X-Test": "Bearer part-${MC_TEST_SECRET}" } }
   ```
   Click **Test connection**.
   **Expected:** `header 'X-Test' uses the variable ${MC_TEST_SECRET}, …` —
   and the message does not contain `part-`: nothing around the placeholder
   is repeated.

## Cleanup

- http://localhost:9091/mcp-gateway → delete `Env Test` and the http test server.
- Restart the admin UI without `MC_TEST_SECRET`.

## Notes

- Why the process environment is out: `/mcp-gateway` asks for a login, not an
  admin role. A header reading `${MC_POSTGRES_PASSWORD}` would hand any
  signed-in user the database password at a URL of their choosing.
- The syntax stays storable on purpose — registrations will not need a rewrite
  once users can set their own variables. The Docker catalog's take-over still
  fills `${GITHUB_PERSONAL_ACCESS_TOKEN}`; replace it with the value.
- Until then a key is entered as a value and stored as entered — in plain text
  in the registration file.
- Automated twin: `McpTargetEndpointsTest` (refusal by field and name, `PATH`
  never read, no default, no value in the message) and `DockerMcpCatalogTest`
  (the take-over placeholder).
