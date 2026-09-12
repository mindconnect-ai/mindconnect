---
id: registry-import-a-package
area: registry
requires: [server-9091, internet]
duration: ~8 min
last-verified: never
---

# A package installs every entity it names, once, in order

**Goal:** Adding a registry, browsing it and importing a package leaves the
agent, the workflow and the LLM config they share in the local stores — and a
second import changes nothing unless overwrite is asked for.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- Outbound HTTPS to raw.githubusercontent.com (otherwise: SKIPPED)
- A public GitHub repository holding the contents of
  `agents/doc/registry-example/` at its root — push that directory as-is. Its
  `owner/repo` is `<REGISTRY>` below.

## Setup

1. Make sure no agent named `changelog-writer`, no LLM config named
   `example-default` and no workflow `greeting` exists yet — delete them if a
   previous run left them behind.

## Steps

1. http://localhost:9091/registry → **Add registry**. Type `<REGISTRY>` into
   **Repository**, leave everything else empty, **Save**.
   **Expected:** Back on the list, one row, named `<REGISTRY>`, subtitle
   `<REGISTRY>@main`.
2. Click the row.
   **Expected:** Four entries — an LLM config, an agent, a workflow and a
   package — each with its kind and version in the subtitle. Nothing says
   "already here".
3. Type `release` into the search field.
   **Expected:** Only **Release kit** remains. Clear it again.
4. Set the kind filter to **Agent**.
   **Expected:** Only `changelog-writer`. Set it back to **Everything**.
5. Click **Release kit**, then **Import**.
   **Expected:** The entry screen comes back with a report under it:
   `3 imported`, one line each for the LLM config, the agent and the workflow,
   in that order — the config before the agent that requires it.
6. http://localhost:9091/admin/agents and http://localhost:9091/admin/llm-configs
   and http://localhost:9091/workflow-admin.
   **Expected:** `changelog-writer` is in the agent list with the prompt from
   the file, `example-default` is in the LLM configs with its API key showing
   the `${ANTHROPIC_API_KEY}` placeholder rather than a key, and `greeting` is
   in the workflow list.
7. Open the agent `changelog-writer` and change its description to `mine`. Save.
   Back to the registry, **Release kit**, **Import** again.
   **Expected:** `3 skipped` — every line says something of that name is already
   here. The agent's description is still `mine`.
8. Same screen, **Import and overwrite**, confirm.
   **Expected:** `3 updated`. The agent's description is back to the registry's,
   and its id in the URL is **unchanged** from step 7 — an overwrite keeps the
   local id.
9. Back on the entry list, one entry at a time: the rows now read
   "already here".

## Cleanup

- Delete the agent `changelog-writer`, the LLM config `example-default` and the
  workflow `greeting`.
- Remove the registry from http://localhost:9091/registry.

## Notes

- The import fetches over the network; the index and the files are cached for
  ten minutes. **Refresh** on the registry screen drops that cache — use it
  after pushing a change to the registry repository mid-test.
- A private registry needs **Token variable** set to the *name* of an
  environment variable holding a GitHub token, and that variable set in the
  server's environment.
