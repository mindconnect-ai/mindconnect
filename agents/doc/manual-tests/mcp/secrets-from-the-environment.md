---
id: mcp-secrets-from-the-environment
area: mcp
requires: [server-9091, server-restartable]
duration: ~6 min
last-verified: 2026-09-11 (working tree on e462251, branch feature/mcp-support, runs/2026-09-11-mcp-support — OpenAI via agent-default)
---

# A secret names where it lives; the registration never holds it

**Goal:** A value in `env` or `headers` may read `${MY_TOKEN}`. The
registration file keeps the placeholder, the started server gets the real
value, and a missing variable says so before anything runs.

## Preconditions

- The admin UI can be restarted with an extra environment variable
  (otherwise: SKIPPED) — this case needs one set for the server process.

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
   **Expected:** The hint under **Target (JSON)** mentions that secrets belong
   in the environment and that `${MY_TOKEN}` is resolved when the server starts.
2. Click **Save**, then open the registration file:
   `cat data/*/system/mcp-servers/envtest.json`
   **Expected:** `"TOKEN": "${MC_TEST_SECRET}"` — **the placeholder, not the
   value**. The string `s3cret-from-outside` appears nowhere in the file.
   Machine check: `grep -r s3cret-from-outside data/ ; echo "exit=$?"` → `exit=1`
3. Click **Test connection**.
   **Expected:** The probe runs and fails on the handshake (`/bin/cat` speaks
   no MCP) — but the server log shows the line `seen: s3cret-from-outside`.
   That is the point: the started process received the **real** value.
4. Edit the registration and change the value to a variable nobody set:
   `"TOKEN": "${MC_DOES_NOT_EXIST}"`. Click **Test connection**.
   **Expected:** Failure in a few milliseconds, before anything is started,
   naming **both** the field and the variable:
   `environment variable 'TOKEN' cannot be resolved: Environment variable
   'MC_DOES_NOT_EXIST' is not set and no default was provided`
5. Change it to a variable with a default: `"TOKEN": "${MC_DOES_NOT_EXIST:fallback}"`.
   Click **Test connection**.
   **Expected:** No resolution error any more — it fails on the handshake as in
   step 3, and the log shows `seen: fallback`.
6. Only if the catalog is enabled (`MC_MCP_CATALOG_ENABLED=true`):
   **Browse catalog** → search `github` → **Take over** on **GitHub Official**.
   **Expected:** The target's `env` carries
   `"GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_PERSONAL_ACCESS_TOKEN}"` — its
   own placeholder, not an empty string. The safe path is the default one.

## Cleanup

- http://localhost:9091/mcp-gateway → `Env Test` → **Delete**.
- Restart the admin UI without `MC_TEST_SECRET`.

## Notes

- Resolution happens in `McpTargetEndpoints`, at the last moment before the
  value is handed to the transport — so the secret never reaches the
  registration file, the discovery cache, the form or a log.
- **Values only, never keys**, and only `env` and `headers`. Image, command
  and URL stay literal: a registration is data, not a template language, and
  the URL in particular must stay checkable against the https rule.
- This is stage 1 of three. The value still sits in the environment of the
  whole server process — encrypted storage and a per-user credential store
  are the later stages.
- Automated twin: `McpTargetEndpointsTest` (resolution, the missing-variable
  error, keys untouched) and `DockerMcpCatalogTest` (the take-over placeholder).
