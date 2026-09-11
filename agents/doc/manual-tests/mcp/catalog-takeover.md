---
id: mcp-catalog-takeover
area: mcp
requires: [server-9091, internet, mcp-catalog-enabled]
duration: ~4 min
last-verified: never
---

# Find a server in the Docker catalog and take it over into the form

**Goal:** The catalog can be searched without starting anything, and taking an
entry over prefills a registration — including the secrets it needs and a tool
name prefix that does not stutter.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- The host may reach `desktop.docker.com` (otherwise: SKIPPED)
- The catalog is switched on: `MC_MCP_CATALOG_ENABLED=true` in the environment
  the server was started with (otherwise: SKIPPED — it is **off by default**,
  see `mcp/gateway-absent.md` for why)

## Steps

1. http://localhost:9091/mcp-gateway.
   **Expected:** A **Browse catalog** button is present next to
   **Register MCP Server**. (Absent ⇒ the catalog is off ⇒ SKIPPED.)
2. Click **Browse catalog**.
   **Expected:** Page **Catalog — Docker MCP Catalog** with a search field and
   a list of entries, each with a **Take over** button.
3. Search for `github`.
   **Expected:** Among the results **GitHub Official**, its description, and a
   line naming how many tools it offers — read from the catalog document
   **without starting a container**.
4. Click **Take over** on **GitHub Official**.
   **Expected:** The registration form opens, prefilled:
   - **Target (JSON)** carries `"type": "docker"` and the entry's image
   - the required secret `GITHUB_PERSONAL_ACCESS_TOKEN` appears in the target's
     `env` with an **empty** value — the requirement is shown, not hidden
   - **Tool name prefix** is filled in and does **not** stutter
   - a note names the catalog entry this came from
5. Do **not** save. Click **All MCP servers**.
   **Expected:** Back on the list; **nothing was registered** — taking over
   prefills a form, it does not create a server.
6. Search the catalog for a term that matches nothing, e.g. `zzzznothing`.
   **Expected:** A readable empty state ("Nothing matches …"), not an error
   and not an empty page.

## Cleanup

- Nothing to undo if step 5 held. If a server was saved by accident:
  http://localhost:9091/mcp-gateway → **Delete**.

## Notes

- The prefix suggestion drops an overlap between the entry id and the tools'
  own prefix: `openbnb-airbnb` offering `airbnb_search` yields `openbnb`, not
  `openbnb_airbnb_airbnb_search`. Automated twin: `ToolNamePrefixesTest`.
- The source is the document Docker Desktop itself fetches, not a documented
  API. If step 3 returns nothing at all while the host is online, the endpoint
  may have moved — that is a FAIL worth a ticket, not a flake.
- The catalog is Docker-shaped: npm- and pypi-based servers and remote HTTP
  ones are not in it. Registering those by hand is `mcp/register-stdio-server.md`
  and `mcp/register-http-server.md`.
