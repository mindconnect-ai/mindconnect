---
title: Skills
sidebar_position: 7
---

# Skills

A **skill** is a piece of know-how written down once and loaded when it is
needed: how a report is formatted here, what a release involves, the checklist
a customer reply has to satisfy.

The point is what a skill costs. Only its **name** and **description** stand in
the system prompt — one line each. The instructions arrive when the model calls
the `skill` tool for that name, and only for the skill it asked about. Ten
skills therefore cost ten lines until one is used, which is what makes it worth
writing them out at length instead of squeezing them into a system prompt.

```text
System prompt                          skill("weekly-report")
─────────────────────────              ─────────────────────────
## Skills                              # Skill: weekly-report
- weekly-report: Use when writing …    Use when writing the weekly report
- release: Use when cutting a release  Source: the project in the working directory
- house-style: Use when writing for …  Directory: /repo/.mindconnect/skills/weekly-report

                                       ## Weekly report
                                       1. Read last week's report under reports/.
                                       2. …
```

## Where skills come from

Three sources, all read fresh on every round — an edited skill is in effect on
the next turn, no restart:

| Source | Where | Who edits it |
|--------|-------|--------------|
| **Managed** | stored by this installation | the **Skills** screen in the admin UI, or `/api/skills` |
| **User** | `~/.mindconnect/skills/` (see below) | the user, in their own files |
| **Project** | `.mindconnect/skills/` in the session's working directory | whoever commits to that repository |

Two skills of the same name are one skill: the more specific source wins,
**project over user over managed**. A project that disagrees with the
installation about how its reports are written is right about its own reports.

## Writing one

A skill is a `SKILL.md` — front matter for the fields, the body for the
instructions. Either layout works:

```text
.mindconnect/skills/weekly-report/SKILL.md   ← a directory, and the files it needs beside it
.mindconnect/skills/release.md               ← a single file, for a skill that needs nothing else
```

```markdown
---
name: weekly-report
description: Use when writing the weekly status report for a customer
tools: file_read, vector_search
---
## Weekly report

1. Read last week's report under `reports/`.
2. Start from `template.md` in this skill's directory.
3. …
```

| Field | Meaning |
|-------|---------|
| `name` | what the model passes to the `skill` tool — lower-case letters, digits and dashes. Defaults to the directory (or file) name. |
| `description` | **the one line the model decides on.** Say *when* to reach for the skill, not what the instructions contain. |
| `tools` | optional; the tools the instructions expect. Named to the model when the skill is loaded — this **grants nothing**, the agent's own bindings still decide what it may call. |

The body is Markdown, handed over whole (cut at 50 000 characters). A skill in
its own directory is handed over with that directory's path, so files beside
the `SKILL.md` — a template, a checklist, a script — are one `file_read` away.

## Giving an agent skills

In the admin UI, open the agent's edit form:

- **Enable Skills** adds the `skill` tool and the prompt section. Off, the
  agent has neither and its prompt says nothing about skills.
- **Skills** narrows it to the skills you pick. Select none and the agent has
  every skill the installation, the user and the project have — a skill added
  later is then in reach without touching every agent.

Through the REST API, the same two values are one field on the agent:

```json
{ "skills": { "enabled": true, "names": ["weekly-report", "release"] } }
```

Sub-agents are agents: a specialist gets its own skills the same way, and a
skill loaded by one is not in any other's context.

## The `skill` tool

The tool is injected, not assigned: an agent with skills switched on has it,
one without does not, and you do not add it to the tool list by hand. It takes
one parameter, `name`, whose allowed values are exactly the skills that agent
may load — so a model cannot invent one, and a wrong guess costs no round.

## Managing skills

The **Skills** screen lists what an agent could load: the stored skills plus
the files in the signed-in user's own skills directory. Stored skills are
created, edited, switched off and deleted there; a skill read from a file is
shown read-only and edited where it lies. Every stored skill can be fetched as
a `SKILL.md` (`/admin/api/skills/{id}/markdown`, or `/api/skills/{id}/markdown`)
— drop that file into a repository's `.mindconnect/skills/` and it travels with
the code.

Skills shipped with the app are imported once on first start from
`initial-data/skills/*.md`; see [Initial data](./initial-data.md). A stored
skill is never overwritten by the shipped version — it is prose someone has
since made their own.

## Configuration

| Property | Environment | Default | Notes |
|----------|-------------|---------|-------|
| `mindconnect.agent.skills.user-dir` | — | `~/.mindconnect/skills` | Where a user's own skills live. Right for a desktop; a server runs as one service account, so put `{user}` in the value (e.g. `/srv/mindconnect/users/{user}/skills`) and each user gets a directory of their own. `off` drops the user scope, leaving the stored skills and the project's. |

Project skills need no configuration: they are read from
`.mindconnect/skills/` in whatever working directory the chat is pointed at,
like a project's [`AGENTS.md`](./prompt-renderer.md).

## Embedding without Spring

```java
AgentRuntime runtime = AgentRuntimeBuilder.fileBased(dataDir)
        .skillFromClasspath("skills/weekly-report.md")
        .agentDefinition(agent.withSkills(AgentDefinition.SkillsConfig.all()))
        .build();
```

## Skills, tools and sub-agents

They answer different questions, and they compose:

- A **tool** is something the agent can *do*. A skill cannot add one.
- A **skill** is how something is *done here* — text, loaded on demand.
- A **sub-agent** is a separate context doing a piece of work and reporting
  back.

A skill that says "use `run_agent` with the `verifier` agent, then check its
answer against the list below" is all three working together.
