---
id: mcp-broken-server-is-contained
area: mcp
requires: [server-9091]
duration: ~5 min
last-verified: never
---

# A server that cannot start costs its own tools and nothing else

**Goal:** A bad registration produces a readable message, keeps what was typed,
and does not take the catalog, the other servers or the running agents with it.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- At least one **working** MCP server registered — `manualfs` from
  `mcp/register-stdio-server.md` does

## Steps

1. http://localhost:9091/mcp-gateway → **Register MCP Server**:
   - **Id**: `manualbroken`
   - **Display name**: `Manual Test Broken`
   - **Tool name prefix**: `brk`
   - **Target (JSON)**:
     ```json
     { "type": "process", "command": ["definitely-not-on-this-path"], "env": {} }
     ```
   Click **Test connection**.
   **Expected:** A **failure** result below the form, naming the actual cause —
   the command is not on PATH — in a sentence, within a second or two. Not a
   30-second timeout, not a stack trace, and **not** an empty form: everything
   typed is still there.
2. Change the target to an unparseable string, e.g. `{ "type": ` and click
   **Save**.
   **Expected:** Message "Target is not valid JSON: …". The form is still on
   screen with the broken text in it, so it can be corrected.
3. Change the target to a valid JSON object with an unknown type:
   `{ "type": "carrier-pigeon" }` and click **Save**.
   **Expected:** Message naming the supported types —
   "Unsupported target type … supported: docker, process, http."
4. Set a prefix the tool names cannot carry: **Tool name prefix** `My Server`,
   target back to the step-1 process target. Click **Save**.
   **Expected:** The save is refused with a message about `toolNamePrefix` and
   the allowed characters. Nothing is registered.
5. Fix the prefix to `brk` and click **Save** (the target still points at a
   missing command).
   **Expected:** The registration **saves** — a server that is unreachable
   right now is still a legitimate registration.
6. Open http://localhost:9091/admin/tools.
   **Expected:** The catalog renders. There is **no** `brk_*` tool, the
   `mfs_*` tools of the working server are **all still there**, and the built-in
   tools are unaffected. On the list page the broken row reads
   `no tools (unreachable?)`.
7. Start a session with an agent bound to `mfs_*` tools and use one.
   **Expected:** It works. A broken registration costs its own tools only.

## Cleanup

- http://localhost:9091/mcp-gateway → `Manual Test Broken` → **Delete**.

## Notes

- Step 1 is deliberately fast: the command is resolved the way the OS would
  before anything is started, so a missing binary fails in a sentence instead of
  in an initialization timeout with a dropped reactive error in the log.
- Step 4 is the regression for a prefix that saved fine and then made **every**
  request of an agent fail at the model provider with a 400 — the tool name it
  produced was not one the provider accepts. Automated twin:
  `McpServerRegistrationTest`.
- A registration file that cannot be parsed at all is skipped with a warning at
  load, for the same reason: one bad file must not cost the others.
