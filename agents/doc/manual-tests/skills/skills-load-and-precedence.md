---
id: skills-load-and-precedence
area: skills
requires: [server-9091, tool-model]
duration: ~10 min
last-verified: never
---

# Skills: created, listed in the prompt, loaded on demand, project wins

**Goal:** A skill created in the admin UI reaches an agent that has skills
switched on — as a name and a description in the system prompt, and as full
instructions only once the agent calls the `skill` tool. A project's skill of
the same name replaces the stored one for a chat working in that project, and
an agent with skills off sees neither.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
- `agent-default` points at a tool-capable model (otherwise: SKIPPED)
- The seeded `coding-assistant` agent exists and is unchanged

## Setup

1. http://localhost:9091/admin/skills → **New Skill**. Fill in:
   - **Name** `manual-test-greeting`
   - **Description** `Use when greeting somebody by name`
   - **Instructions**
     ```
     Greet with exactly: "Salutations, <name>, from the installation."
     Say nothing else.
     ```
   - Leave **Tools it expects** empty, **Enabled** on.
   **Save**.
2. Create the project directory and its skill:
   ```bash
   mkdir -p ~/mc-manual-tests/skills-project/.mindconnect/skills
   cat > ~/mc-manual-tests/skills-project/.mindconnect/skills/manual-test-greeting.md <<'MD'
   ---
   description: Use when greeting somebody by name
   ---
   Greet with exactly: "Salutations, <name>, from the project."
   Say nothing else.
   MD
   ```
3. http://localhost:9091/admin/agents → `coding-assistant` → **Edit** →
   **Enable Skills** on, **Skills** empty (= all) → **Save**.

## Steps

1. On the agent's detail page, read the **Skills** row.
   **Expected:** `all skills`.
2. Open http://localhost:9091/chat, **New chat**, **Model & tools** →
   **Agent** `coding-assistant` → **Apply**. Do **not** choose a folder.
   Send: `Greet Alice by name.`
   **Expected:** A `skill` tool card with argument
   `{"name": "manual-test-greeting"}`, and the answer
   `Salutations, Alice, from the installation.`
3. Open the session's working memory: the session id is in the chat URL, then
   http://localhost:9091/admin/sessions/&lt;id&gt; → **Memory**.
   **Expected:** The system prompt contains a `## Skills` section with the line
   `- manual-test-greeting: Use when greeting somebody by name`, and does
   **not** contain the word `Salutations` — the instructions are not in the
   prompt, only in the tool result.
4. **New chat**, same agent, then the folder button → absolute path of
   `~/mc-manual-tests/skills-project` → **Open** → **Use this folder**.
   Send: `Greet Alice by name.`
   **Expected:** The answer is `Salutations, Alice, from the project.` — the
   project's file replaced the stored skill of the same name.
5. Edit the project file (change `project` to `project, edited`), then send in
   the **same** chat: `Greet Bob by name.`
   **Expected:** `Salutations, Bob, from the project, edited.` — skills are
   read fresh, no restart.
6. Admin UI → `coding-assistant` → **Edit** → **Enable Skills** off → **Save**.
   **New chat** with the agent, send: `Greet Alice by name.`
   **Expected:** No `skill` tool card, and the answer is an ordinary greeting.
   The memory view of that session has no `## Skills` section.
7. Admin UI → `coding-assistant` → **Edit** → **Enable Skills** on, and in
   **Skills** select only a skill that is *not* `manual-test-greeting` (create
   a second one if the list has no other) → **Save**. **New chat**, send:
   `Greet Alice by name.`
   **Expected:** The prompt's `## Skills` section does not list
   `manual-test-greeting`; if the model calls `skill` with that name anyway the
   result is `No skill named 'manual-test-greeting'.`

## Cleanup

- http://localhost:9091/admin/skills → delete `manual-test-greeting` (and the
  second skill from step 7, if created).
- `rm -rf ~/mc-manual-tests/skills-project`
- `coding-assistant` → **Edit** → **Enable Skills** off, **Skills** empty →
  **Save**.

## Notes

- Steps 2, 4 and 5 depend on the model actually reaching for the tool. If it
  answers without calling `skill` at all, re-send once with
  `Use your skills.`; if it still does not, the case is SKIPPED for the model,
  not FAILED — but step 3 (the prompt section) must hold either way.
- The skill name must be lower-case letters, digits and dashes; the form
  refuses anything else with a toast rather than saving it.
