---
title: Skills
sidebar_position: 4
---

# Skills

The **Skills** section manages the instruction packs an agent loads on demand.
A skill's name and description stand in the agent's prompt; the instructions
themselves arrive only when the agent calls the `skill` tool for it. The
concept — the three sources, the file format, how one is written — is
[Skills](../skills.md); this page is the screen.

## The list

It shows what an agent could actually load: the skills this installation
stores, plus the `SKILL.md` files in the signed-in user's own skills directory.
A skill a project keeps in its `.mindconnect/skills/` belongs to a session's
working directory and shows up in the chat that works there, not here.

- **New Skill** — create one.
- **Delete** — on a stored skill's row.
- A row from a file says so and has no buttons: it is edited where it lies,
  and this screen has no business writing into somebody's repository or home
  directory.
- A skill switched off is listed as `(off)` and offered to no agent.

## The form

| Field | What it is for |
|-------|----------------|
| **Name** | What the model passes to the `skill` tool: lower-case letters, digits and dashes. |
| **Description** | The one line the model decides on. Say **when** to reach for the skill — it is in the prompt from the first token, the instructions are not. |
| **Instructions** | Markdown, handed over whole when the skill is loaded. Write it as long as the work needs; it costs nothing until it is used. |
| **Tools it expects** | Named to the model on load. Grants nothing — the agent's own tools still decide what it may call. |
| **Enabled** | Off keeps the skill here and offers it to nobody. |

Saving checks the version the form was opened with: if someone else saved the
skill meanwhile, yours is refused rather than silently overwriting theirs.

## Giving an agent skills

On the agent's edit form ([Agents](./agents.md)):

- **Enable Skills** — adds the `skill` tool and the prompt section.
- **Skills** — the skills this agent may load. Select none and it gets every
  skill there is, so a skill added later is in reach without touching every
  agent.

The agent's detail view shows the result as one line: `off`, `all skills`, or
the names.

## Taking a skill to a repository

`/admin/api/skills/{id}/markdown` returns any skill as a `SKILL.md` — front
matter and body. Put that file under a project's `.mindconnect/skills/` and it
travels with the code, and the project's version then wins over the stored one
for sessions working there.
