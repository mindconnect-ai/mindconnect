---
id: registry-import-a-package
area: registry
requires: [server-9090, internet]
duration: ~8 min
last-verified: 2026-09-17 (commit a561e9ca, runs/2026-09-16-full-suite)
---

# A package installs every entity it names, once, in order

**Goal:** Adding a registry, browsing it and importing a package leaves the
agent, the workflow, the skill and the LLM config they share in the local stores — and a
second import changes nothing unless overwrite is asked for.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- Outbound HTTPS to raw.githubusercontent.com (otherwise: SKIPPED)
- A public GitHub repository holding the contents of
  `agents/doc/registry-example/` at its root — push that directory as-is. Its
  `owner/repo` is `<REGISTRY>` below.
  Without one (no repository may be created), mirror the raw layout locally
  instead: copy `agents/doc/registry-example/` to
  `<dir>/manual/registry-example/main/`, serve `<dir>` over http (e.g.
  `jwebserver -p 8766 -b 127.0.0.1 -d <dir>`), use `manual/registry-example`
  as `<REGISTRY>` and put `http://localhost:8766` into **Raw content URL** in
  step 1. Everything but the GitHub host is exercised the same way.

## Setup

1. Make sure no agent named `changelog-writer`, no LLM config named
   `example-default`, no workflow `greeting` and no skill `changelog-style`
   exists yet — delete them if a previous run left them behind.

## Steps

1. http://localhost:9090/registry → **Add registry**. Type `<REGISTRY>` into
   **Repository**, leave everything else empty, **Save**.
   **Expected:** Back on the list, one row, named `<REGISTRY>`, subtitle
   `<REGISTRY>@main`.
2. Click the row.
   **Expected:** Five entries — an LLM config, an agent, a workflow, a skill
   and a package — in collapsed sections per kind (**Agents (1)**, **LLM
   configs (1)**, **Workflows (1)**, **Skills (1)**, **Packages (1)**), each
   with its kind and version in the subtitle and an **Import** button. Nothing
   says "already here".
3. Type `kit` into the search field.
   **Expected:** Only **Release kit** remains. Clear it again. (The search
   also matches tags: `release` would keep `changelog-writer` and
   `changelog-style` too, which carry the tag `release`.)
4. Set the kind filter to **Agent**.
   **Expected:** Only `changelog-writer`. Set it back to **Everything**.
5. Click **Release kit**, then **Import**, and confirm the browser dialog.
   **Expected:** The entry screen comes back with a report under it:
   `4 imported`, one line each for the LLM config, the agent, the workflow and
   the skill, in that order — the config before the agent that requires it.
6. http://localhost:9090/admin/agents and http://localhost:9090/admin/llm-configs
   and http://localhost:9090/workflow-admin.
   **Expected:** `changelog-writer` is in the agent list with the prompt from
   the file, `example-default` is in the LLM configs with its API key holding
   the `${ANTHROPIC_API_KEY}` placeholder rather than a key (the detail page
   masks every key as `••••••••`; the placeholder is the value of the **API
   Key** field under **Edit**, and in the stored config JSON), and `greeting` is
   in the workflow list. http://localhost:9090/admin/skills lists
   `changelog-style` with the instructions from the file.
7. Open the agent `changelog-writer` and change its description to `mine`. Save.
   Back to the registry, **Release kit**, **Import** again.
   **Expected:** `4 skipped` — every line says something of that name is already
   here. The agent's description is still `mine`.
8. Same screen, **Import and overwrite**, confirm.
   **Expected:** `4 updated`. The agent's description is back to the registry's,
   and its id in the URL is **unchanged** from step 7 — an overwrite keeps the
   local id.
9. Back on the entry list, one entry at a time: the rows now read
   "already here".

## Cleanup

- Delete the agent `changelog-writer`, the LLM config `example-default`, the
  workflow `greeting` and the skill `changelog-style`.
- Remove the registry from http://localhost:9090/registry.

## Notes

- The import fetches over the network; the index and the files are cached for
  ten minutes. **Refresh** on the registry screen drops that cache — use it
  after pushing a change to the registry repository mid-test.
- A private registry needs **Token variable** set to the *name* of an
  environment variable holding a GitHub token, and that variable set in the
  server's environment.
