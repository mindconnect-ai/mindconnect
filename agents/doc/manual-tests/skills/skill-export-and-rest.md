---
id: skills-skill-export-and-rest
area: skills
requires: [server-9090]
duration: ~6 min
last-verified: 2026-09-16 (commit f86ac52e, runs/2026-09-16-full-suite)
---

# Skills over REST: create, list, update, delete — and export as SKILL.md

**Goal:** `/api/skills` creates, lists, reads, updates and deletes stored
skills with the refusals its contract names (a name no model could type is a
`400`, a taken name and a stale version are `409`), and any stored skill can be
fetched as a portable `SKILL.md`. The admin UI shows what the API stored and
refuses the same names. No LLM is needed.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- `curl`, `jq`

## Setup

```bash
B=http://localhost:9090
c() { printf '%-26s %s\n' "$1" "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${@:2}")"; }
for n in manual-rest-skill manual-rest-two manual-rest-renamed; do
  ID=$(curl -s $B/api/skills | jq -r --arg n $n '.[] | select(.name==$n) | .id')
  [ -n "$ID" ] && curl -s -o /dev/null -X DELETE $B/api/skills/$ID
done
```

## Steps

1. **Create.**
   ```bash
   curl -s -X POST $B/api/skills -H 'Content-Type: application/json' -d '{
     "name": "manual-rest-skill",
     "description": "Use when a manual test asks for the REST skill",
     "instructions": "## Steps\n1. Say REST-SKILL-OK.\n2. Stop.",
     "tools": ["file_read", "grep"]
   }' | tee /tmp/skill.json | jq '{id, name, enabled, source, version, tools}'
   SK=$(jq -r .id /tmp/skill.json)
   ```
   **Expected:** `200`; `"enabled": true`, `"source": "MANAGED"`,
   `"version": 1`, `"tools": ["file_read", "grep"]`, and an `id`.

2. **List and read.**
   ```bash
   curl -s $B/api/skills | jq -r '.[].name' | grep -x manual-rest-skill
   c "get by id" $B/api/skills/$SK
   c "get unknown id" $B/api/skills/no-such-skill
   ```
   **Expected:** the name is printed; `200` by id, `404` for the unknown id.

3. **Fetch as SKILL.md.**
   ```bash
   curl -s -D - $B/api/skills/$SK/markdown
   ```
   **Expected:** `200`, a `Content-Type` starting with `text/markdown`, and
   exactly this body:
   ```
   ---
   name: manual-rest-skill
   description: Use when a manual test asks for the REST skill
   tools: file_read, grep
   ---

   ## Steps
   1. Say REST-SKILL-OK.
   2. Stop.
   ```
   The admin UI serves the same text at
   http://localhost:9090/admin/api/skills/&lt;SK&gt;/markdown. There is no
   download button on the skill's page — the URL is the export.

4. **Invalid names are refused, with the name folded to lower case.**
   ```bash
   curl -s -w ' [%{http_code}]\n' -X POST $B/api/skills -H 'Content-Type: application/json' -d '{"name":"Manual Rest","instructions":"x"}'
   curl -s -w ' [%{http_code}]\n' -X POST $B/api/skills -H 'Content-Type: application/json' -d '{"name":"-manual","instructions":"x"}'
   curl -s -w ' [%{http_code}]\n' -X POST $B/api/skills -H 'Content-Type: application/json' -d '{"name":"manual_rest","instructions":"x"}'
   ```
   **Expected:** each answers `400` with
   `Skill name '<name>' is not usable: use lower-case letters, digits and dashes, starting with a letter or digit, at most 64 characters`
   — for the first one `<name>` is `manual rest` (lower-cased), then `-manual`,
   then `manual_rest`.

5. **Upper case alone is folded, not refused.**
   ```bash
   curl -s -X POST $B/api/skills -H 'Content-Type: application/json' -d '{"name":"Manual-REST-Two","instructions":"x","enabled":false}' | jq '{name, enabled}'
   ```
   **Expected:** `{"name": "manual-rest-two", "enabled": false}`.

6. **A taken name is a conflict — on create and on rename.**
   ```bash
   curl -s -w ' [%{http_code}]\n' -X POST $B/api/skills -H 'Content-Type: application/json' -d '{"name":"manual-rest-skill","instructions":"x"}'
   curl -s -w ' [%{http_code}]\n' -X PUT $B/api/skills/$SK -H 'Content-Type: application/json' -d '{"name":"manual-rest-two"}'
   ```
   **Expected:** both `409`; bodies
   `A skill named 'manual-rest-skill' already exists` and
   `A skill named 'manual-rest-two' already exists`.

7. **Partial update, with and without a version.**
   ```bash
   curl -s -X PUT $B/api/skills/$SK -H 'Content-Type: application/json' -d '{"description":"Changed by PUT","version":1}' | jq '{description, instructions, tools, version}'
   curl -s -w ' [%{http_code}]\n' -X PUT $B/api/skills/$SK -H 'Content-Type: application/json' -d '{"description":"Stale","version":1}'
   curl -s -X PUT $B/api/skills/$SK -H 'Content-Type: application/json' -d '{"name":"manual-rest-renamed","enabled":false}' | jq '{name, description, enabled, version}'
   ```
   **Expected:**
   - first: `description` changed, `instructions` and `tools` unchanged, `"version": 2`;
   - second: `409`, body contains `was changed meanwhile (edited version 1, stored version 2)`;
   - third (no version — applied to what is stored): `"name": "manual-rest-renamed"`,
     `"description": "Changed by PUT"`, `"enabled": false`, `"version": 3`.
   `c "put unknown" -X PUT $B/api/skills/no-such-skill -H 'Content-Type: application/json' -d '{}'` → `404`.

8. **The admin UI shows what the API stored.** http://localhost:9090/admin/skills
   **Expected:** rows `manual-rest-renamed (off)` with the description
   `Changed by PUT`, and `manual-rest-two (off)`. Click
   `manual-rest-renamed`: **Source** `this installation`, **Tools it expects**
   `file_read, grep`, **Enabled** off, **Instructions** rendered with the heading
   `Steps`. Typing `renamed` into the search field
   (`Search name or description…`) and pressing **Enter** leaves only that row
   — the header then reads `Skills (1)`. Typing alone does not filter.

9. **The form refuses the same names.** **New Skill** → **Name** `Bad Name`,
   **Instructions** `x` → **Save**.
   **Expected:** an error toast titled `Skill not saved`:
   `'bad name' cannot be used as a name here: use lower-case letters, digits and dashes, starting with a letter or digit.`
   Then **Name** `manual-rest-two` → **Save**: toast `Skill not saved`,
   `There is already a skill named 'manual-rest-two'. Choose another name, or edit that one.`

10. **Delete.**
    ```bash
    c "delete" -X DELETE $B/api/skills/$SK
    c "delete again" -X DELETE $B/api/skills/$SK
    c "get deleted" $B/api/skills/$SK
    c "markdown of deleted" $B/api/skills/$SK/markdown
    ```
    **Expected:** `204`, then `404` three times. The row is gone from
    http://localhost:9090/admin/skills after a reload.

## Cleanup

- Delete `manual-rest-two` (and `manual-rest-skill` / `manual-rest-renamed` if a
  FAIL left them) — the loop in Setup does it.
- `rm -f /tmp/skill.json`

## Notes

- Skills live per namespace: without an `X-Mindconnect-Namespace` header the
  API works in the default namespace, whatever the admin UI has selected.
- `/api/skills` lists the stored (managed) skills only; a user's or a
  project's `SKILL.md` files are not listed and not writable here.
- The name check runs before the conflict check, so an unusable name is a
  `400` even when a skill of that (folded) name exists.
