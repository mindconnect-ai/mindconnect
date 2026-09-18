---
id: skills-skill-import-from-registry
area: skills
requires: [server-9090, internet, tool-model]
duration: ~12 min
last-verified: never
---

# A skill imported from a registry is a managed skill an agent can load

**Goal:** A `skill` entry of a registry imports as a managed skill: it shows on
the Skills screen, an agent that names it loads it, a re-import keeps the local
id and the enabled flag. A package that would take the skill away shows which
agents name it and leaves it out of the removal unless it is ticked.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- Outbound HTTPS to raw.githubusercontent.com (otherwise: SKIPPED)
- A public GitHub repository holding the contents of
  `agents/doc/registry-example/` at its root. Its `owner/repo` is `<REGISTRY>`
  below (otherwise: SKIPPED). It has to be the root: pointing a registry at
  `mindconnect-ai/mindconnect@main:agents/doc/registry-example/registry.json`
  reads the index, but the entry paths in it are resolved against the
  repository root, so every entry would 404. `mindconnect-ai/mc-registry` holds
  different entries and does not stand in for it.
- `agent-default` points at a tool-capable model — steps 7–8 only
  (otherwise those steps are SKIPPED)
- Not run in parallel with `registry/import-a-package.md` — both use the same
  entity names
- `curl`, `jq`

## Setup

1. No skill `changelog-style`, no agents `changelog-writer` or
   `manual-skill-user`, no LLM config `example-default`, no workflow `greeting`
   — delete leftovers from a previous run (Skills, Agents, LLM Configs screens,
   http://localhost:9090/workflow-admin).
2. http://localhost:9090/registry lists `<REGISTRY>`; if not, **Add registry**
   → **Repository** `<REGISTRY>` → **Save**.
3. ```bash
   B=http://localhost:9090
   ```

## Steps

1. http://localhost:9090/registry → click the `<REGISTRY>` row.
   **Expected:** the entries are grouped; one group is **Skills  (1)** with the
   row `changelog-style`, subtitle
   `Skill · 1.0.0 · by mindconnect · How a changelog entry is written — loaded by an agent on demand`,
   and an **Import** action. Set the kind filter to **Skill**: only
   `changelog-style` remains. Leave the filter on **Skill**.

2. Click `changelog-style`.
   **Expected:** the entry page shows **Kind** `Skill`, **Version** `1.0.0`,
   **From** `<REGISTRY>@main · skills/changelog-style/SKILL.md`, and the note
   starting `A skill is instructions an agent loads on demand` and containing
   `A skill from a registry is its SKILL.md alone: files beside it in the repository are not brought along.`
   Buttons: **Import**, **Import and overwrite**, **Back to <name>**.

3. **Back to <name>**, then **Import** in the `changelog-style` row — no
   dialog, the import runs from the list.
   **Expected:** the registry list comes back with the kind filter still on
   **Skill** and a report above it: `✓ changelog-style — 1 imported` and
   `Skill 'changelog-style' — imported`. The row now carries the **Already
   here** badge and its action reads **Overwrite**. Set the filter back to
   **Everything**.

4. http://localhost:9090/admin/skills → click `changelog-style`.
   **Expected:** the list row shows the description; the detail page has
   **Source** `this installation`, **Tools it expects** `file_read`,
   **Enabled** on, the actions **Edit** and **Delete**, and under
   **Instructions** the heading `How a changelog entry is written here`.
   Via REST:
   ```bash
   curl -s $B/api/skills | jq '.[] | select(.name=="changelog-style") | {id, source, enabled, tools, version}'
   ```
   `"source": "MANAGED"`, `"enabled": true`, `"tools": ["file_read"]`. Note the
   `id` as `SK`.

5. **Edit** → switch **Enabled** off → **Save**. Back on
   http://localhost:9090/admin/skills the row reads `changelog-style (off)`.
   Return to the registry entry page of `changelog-style`.
   **Expected:** the first button now reads **Import, keep mine**, and the
   entry's row on the registry list carries the **Already here** badge with an
   **Overwrite** action.

6. **Import and overwrite** → confirm.
   **Expected:** `✓ 1 updated`, `Skill 'changelog-style' — updated`. And:
   ```bash
   curl -s $B/api/skills | jq '.[] | select(.name=="changelog-style") | {id, enabled}'
   ```
   `id` is still `SK`, `enabled` is still `false` — a re-import keeps both.
   Now switch **Enabled** back on in the skill's **Edit** form and **Save**.

7. An agent that names the skill:
   ```bash
   AG=$(curl -s -X POST $B/api/agents -H 'Content-Type: application/json' \
     -d '{"name":"manual-skill-user","description":"Manual test: names one skill","systemPrompt":"You write changelog entries. Use your skills.","llmConfigName":"agent-default"}' | jq -r .id)
   curl -s -X PUT $B/api/agents/$AG -H 'Content-Type: application/json' \
     -d '{"skills":{"mode":"SPECIFIC","names":["changelog-style"]}}' | jq .skills
   ```
   **Expected:** `{"mode": "SPECIFIC", "names": ["changelog-style"]}`; the
   agent's detail page at http://localhost:9090/admin/agents shows the
   **Skills** row `changelog-style`.

8. http://localhost:9090/admin/agents → row `manual-skill-user` → **Chat**.
   Send: `Write a changelog entry for: the upload no longer crashes on an empty file.`
   **Expected:** a `skill` tool card with `{"name": "changelog-style"}`, then an
   entry that follows the skill (leads with what is different for the user,
   names the area in bold). In the session's **Working Memory** view the system
   prompt has a `## Skills` section with the line
   `- changelog-style: Use when writing or reviewing a changelog entry, so it reads for someone deciding whether to upgrade`.

9. Registry → **Release kit** → tab **Contents (4)**.
   **Expected:** the row `changelog-style` says `Already here` and
   `Used by Agent 'manual-skill-user'`, and its **Include** box is unticked;
   the other three rows say `New` and are ticked.

10. **Import** → confirm.
    **Expected:** `✓ 3 imported, 1 skipped`, with the line
    `Skill 'changelog-style' — skipped (left out of this import)`.

11. Open **Release kit** again from the registry list (so the boxes start from
    their defaults), leave them as they are, **Remove** → confirm.
    **Expected:** `✓ 3 removed, 1 skipped`; the agent `changelog-writer`, the
    workflow `greeting` and the LLM config `example-default` are `removed`, and
    the last line is `Skill 'changelog-style' — skipped (kept — left out of the removal)`.
    http://localhost:9090/admin/skills still lists `changelog-style`.

12. **Release kit** again → **Contents** → tick **Include** on
    `changelog-style` → **Remove** → confirm.
    **Expected:** the line `Skill 'changelog-style' — removed`; the other three
    are `skipped` (nothing of that name is here). `curl -s $B/api/skills | jq '[.[] | select(.name=="changelog-style")] | length'`
    prints `0`. The agent `manual-skill-user` still names it (its detail page
    still shows **Skills** `changelog-style`) — removing a skill does not edit
    the agents that name it.

## Cleanup

- `curl -s -o /dev/null -X DELETE $B/api/agents/$AG`
- If a FAIL left them: delete the skill `changelog-style`, the agent
  `changelog-writer`, the LLM config `example-default`, the workflow `greeting`.
- Remove `<REGISTRY>` from http://localhost:9090/registry if Setup added it.

## Notes

- Step 8 depends on the model reaching for the tool; re-send once with
  `Use your skills.` If it still does not call `skill`, the step is SKIPPED for
  the model — the `## Skills` line must be there either way.
- "Used by" counts only agents that *name* the skill (`SPECIFIC` mode). An
  agent in `ALL` mode depends on it just as much but is not listed.
- The registry caches index and files for ten minutes; **Refresh** on the
  registry's page drops the cache after a push to `<REGISTRY>`.
- The import stores the name from the SKILL.md front matter, while Remove and
  the "already here" check look the skill up by the registry entry's `name`.
  The example uses the same name in both places; a registry where they differ
  is not covered here.
