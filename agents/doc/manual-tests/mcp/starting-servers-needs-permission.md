---
id: mcp-starting-servers-needs-permission
area: mcp
requires: [server-9091, server-restartable]
duration: ~10 min
last-verified: 2026-09-11 (working tree on e0d19d7, branch fix/mcp-review-1-4, port 9097, probes sent to /mcp-gateway/api/probe instead of through the form)
---

# Process and docker targets start only where the installation allows them

**Goal:** A `process` or `docker` target runs code with the rights of the admin
UI, and registering asks for a login, not an admin role. Whether such targets
start is therefore the installation's decision: `mindconnect.mcp.allow-process`
and `mindconnect.mcp.allow-docker`, which — unset — are on while sign-in is off
and off once it is on. An `http` target needs neither. A server that does start
inherits only what a command needs (`PATH`, `HOME`, locale, proxies, the
container daemon), never the secrets of the admin UI process.

## Preconditions

- The admin UI can be restarted with extra environment variables
  (otherwise: SKIPPED).

## Setup

1. Stop the admin UI and start it with both kinds switched off and a test
   secret in its environment:
   `MINDCONNECT_MCP_ALLOW_PROCESS=false MINDCONNECT_MCP_ALLOW_DOCKER=false MC_TEST_SECRET=s3cret-from-outside ./agents/server/mc-agent-admin-ui-app/start.sh`
2. **Expected** in the start log:
   `MCP servers started on this machine: process targets switched off, docker targets switched off`.

## Steps

1. http://localhost:9091/mcp-gateway → **Register MCP Server**:
   - **Id**: `envdump`
   - **Tool name prefix**: `envdump`
   - **Target (JSON)**:
     ```json
     { "type": "process", "command": ["/bin/sh", "-c", "env >&2; exec /bin/cat"], "env": { "MINE": "from-the-registration" } }
     ```
   Click **Test connection**.
   **Expected:** Failure within milliseconds:
   `process targets are switched off on this installation: a process runs with
   the rights of this server. An operator allows them with
   mindconnect.mcp.allow-process=true`. The server log has no `[mcp:/bin/sh]` line.
2. Replace the target with a container:
   ```json
   { "type": "docker", "image": "alpine:3", "runFlags": ["--privileged"], "command": ["env"] }
   ```
   Click **Test connection**.
   **Expected:** `docker targets are switched off on this installation: …
   mindconnect.mcp.allow-docker=true`; no container was started
   (`docker ps -a --filter ancestor=alpine:3` shows nothing new).
3. Replace the target with `{ "type": "http", "url": "https://mcp.deepwiki.com/mcp" }`.
   Click **Test connection**.
   **Expected:** Success with DeepWiki's tools — an http target needs no permission.
4. Stop the admin UI and start it again with the secret but **without** the two
   flags (sign-in is off locally, so both default to on):
   `MC_TEST_SECRET=s3cret-from-outside ./agents/server/mc-agent-admin-ui-app/start.sh`
   **Expected** in the start log: `process targets allowed, docker targets allowed`.
5. Register the `process` target from step 1 again and click **Test connection**.
   **Expected:** The probe starts the process and fails on the handshake
   (`/bin/cat` speaks no MCP). The log lines `[mcp:/bin/sh] …` show `PATH=`,
   `HOME=` and `MINE=from-the-registration`, and **no** `MC_TEST_SECRET`,
   `MINDCONNECT_ENCRYPTION_SECRET_KEY` or `MC_POSTGRES_PASSWORD`.

## Cleanup

- http://localhost:9091/mcp-gateway → delete `envdump` if it was saved.
- Restart the admin UI without `MC_TEST_SECRET`.

## Notes

- With the `keycloak` profile (`mindconnect.auth.enabled=true`) both flags
  default to off; an installation that wants such servers sets them explicitly.
- The flags stand in for an admin role on `/mcp-gateway`, which needs a role
  model the admin UI does not have yet (concept 21 §9.2).
- Automated twins: `McpStartPolicyTest` (defaults), `McpTargetEndpointsTest`
  (refusal before anything starts), `McpGatewayAutoConfigurationTest` (a probe
  with sign-in on), `InheritedEnvironmentTest` (what a started server inherits).
