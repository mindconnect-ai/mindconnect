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
   with its kind and version in the subtitle and an **Import** button that
   installs from the list. No row carries an **Already here** badge.
3. Type `kit` into the search field.
   **Expected:** Only **Release kit** remains. Clear it again. (The search
   also matches tags: `release` would keep `changelog-writer` and
   `changelog-style` too, which carry the tag `release`.)
4. Set the kind filter to **Agent**.
   **Expected:** Only `changelog-writer`. Set it back to **Everything**.
5. Open **Packages (1)** and press **Import** in the **Release kit** row.
   **Expected:** No dialog. The registry list comes back — same screen, same
   filters — with a report above it: `✓ Release kit — 4 imported`, one line
   each for the LLM config, the agent, the workflow and the skill, in that
   order — the config before the agent that requires it. The rows of those
   four now carry the **Already here** badge.
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
   Back to the registry list, **Import** in the **Release kit** row again.
   **Expected:** `✓ Release kit — 4 skipped` — every line says something of that
   name is already here. The agent's description is still `mine`.
8. Click **Release kit** to open it, then **Import and overwrite**, confirm.
   **Expected:** `4 updated`. The agent's description is back to the registry's,
   and its id in the URL is **unchanged** from step 7 — an overwrite keeps the
   local id. (A package's row on the list only ever offers **Import**: whether
   a package as such is here is not something this installation knows, only
   whether its members are.)
9. **Back to <name>**, then **Overwrite** in the `changelog-writer` row, confirm
   the dialog.
   **Expected:** the list comes back with `✓ changelog-writer — 1 updated`, and
   the rows of the agent, the workflow, the skill and the LLM config all carry
   the **Already here** badge.
10. Look at the rubric headings.
    **Expected:** none of **Agents**, **LLM configs**, **Workflows** or
    **Skills** carries an **Import missing (n)** button any more — everything
    the registry offers is here. **Packages** never carries one.
11. Delete the skill `changelog-style` on http://localhost:9090/admin/skills and
    come back to the registry list (**Refresh** is not needed — the badge reads
    the local store, not the index).
    **Expected:** the **Skills** rubric heading carries **Import missing (1)**.
    Press it and confirm `Import the 1 entry of Skills in <REGISTRY>@main that
    are not here yet?`.
    **Expected:** the list comes back with `✓ Skills — 1 imported`, the Skills
    rubric open, `changelog-style` back with its **Already here** badge, and the
    button gone.

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
