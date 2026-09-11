---
id: mcp-registration-change-without-restart
area: mcp
requires: [server-9091, npx]
duration: ~5 min
last-verified: never
---

# A changed registration takes effect without a restart

**Goal:** Editing, disabling and re-reading a registration is visible at the
next lookup — and a pooled connection to the old configuration is dropped
rather than kept alive.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- The server `manualfs` from `mcp/register-stdio-server.md` is registered

## Steps

1. http://localhost:9091/admin/tools, search `mfs`.
   **Expected:** The tools appear under section **Manual Test Filesystem**.
2. http://localhost:9091/mcp-gateway → row `Manual Test Filesystem`. Change
   **Display name** to `Renamed Filesystem` and click **Save**.
   **Expected:** The list shows `Renamed Filesystem`.
3. Reload http://localhost:9091/admin/tools and search `mfs` — **without**
   restarting the server.
   **Expected:** The section is now called **Renamed Filesystem**. The tool
   names are unchanged (they follow the prefix, not the display name).
4. Edit the registration again, switch **Enabled** off, **Save**.
   **Expected:** The row's line ends in `· disabled`, and it no longer names a
   tool count — a disabled server is not asked.
5. Reload http://localhost:9091/admin/tools, search `mfs`.
   **Expected:** **No** `mfs_*` tools at all — a disabled *server* takes its
   whole bundle out, which is different from a disabled *tool*
   (`mcp/disable-and-reenable-tool.md`).
6. Switch **Enabled** back on, **Save**, reload the catalog.
   **Expected:** The `mfs_*` tools are back.
7. Open the registration and click **Re-read tools**.
   **Expected:** The page returns with a **Known tools** section listing the
   server's tools and a fresh timestamp — the server was asked again, now,
   rather than at some later lookup.
8. Look at **Tool name prefix** in the edit form.
   **Expected:** It is **read-only**, showing `mfs`, with a hint saying it is
   fixed after creation and that a different one needs a new registration.
   There is no way to change it from this screen.
9. Reach past the form — send a different prefix straight to the API:
   ```bash
   curl -s -X POST localhost:9091/mcp-gateway/api/manualfs/save \
     -H 'Content-Type: application/json' \
     -d '{"displayName":"Manual Test Filesystem","enabled":true,"toolNamePrefix":"mfs2",
          "target":"{\"type\":\"process\",\"command\":[\"/bin/echo\",\"hi\"],\"env\":{}}"}'
   ```
   **Expected:** The response carries a refusal naming both prefixes —
   "The tool name prefix is fixed after creation … It stays 'mfs'. Register a
   new server to use 'mfs2'." Reload http://localhost:9091/admin/tools:
   the tools are still `mfs_*`, and the registration is unchanged.
   A silently dropped field would be worse than a missing one — this one
   would take every agent's binding with it.

## Cleanup

- Ensure the prefix is `mfs`, **Enabled** is on and the display name is
  `Manual Test Filesystem` again — or delete the registration entirely if the
  following cases do not need it.

## Notes

- Discovery is cached twice: in memory for the catalog and on disk under
  `data/<namespace>/system/mcp-schema-cache/`. Both are keyed on the repository's version,
  so an edit invalidates them without a restart. **Re-read tools** drops the
  disk cache explicitly, for the case where the *server* changed but its
  registration did not (a new image tag behind the same name).
- Saving also closes pooled connections to that server, so the next call does
  not keep talking to the old image, URL or token.
- Steps 8 and 9 are the answer to that: the prefix is the head of every tool
  name the server contributes, agents bind those names, and renaming it used
  to orphan their bindings, the operator's tool settings and any standing
  approval — silently. It is fixed after creation instead (concept 23 §5).
  To use a different one, register a new server.
