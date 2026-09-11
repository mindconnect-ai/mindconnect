---
title: Prompt renderer
sidebar_position: 5
---

# Prompt renderer

An agent's `systemPrompt` is a **template**, not a fixed string. Before each
turn the runtime renders it against a live context — the date, the agent's own
metadata, its tools, the user's notes, the current todo list — so the same
definition produces a prompt tailored to the moment.

## Templating with Pebble

The default `PromptRenderer` uses [Pebble](https://pebbletemplates.io/) (a
Jinja2-style engine). Three constructs cover almost everything:

```text
{{ current_date }}                      # insert a variable
{% if todo_list_md %} … {% endif %}     # conditional block
{% for item in items %} … {% endfor %}  # loop
```

A plain prompt with no `{{ }}` or `{% %}` passes through unchanged, so existing
text prompts keep working. If a template fails to render, the runtime returns
the original string rather than crashing the turn.

Example from a bundled agent:

```text
Today is {{ current_date }}.

{% if user_notes %}
What you remember about this user:
{{ user_notes }}
{% endif %}

{% if todo_list_md %}
Your current plan:
{{ todo_list_md | raw }}
{% endif %}
```

## Where the variables come from

Variables are contributed by **`PromptContextProvider`** beans. Each provider
adds entries to the context map; they are discovered automatically by Spring, so
adding a new variable means adding a provider class — no change to existing code.

Providers run in `priority()` order (ascending), so a later provider can
override an earlier one. The built-in providers:

| Provider | Variables |
|----------|-----------|
| `CurrentDateProvider` | `current_date`, `current_datetime`, `current_time` |
| `AgentMetadataProvider` | `agent_name`, `agent_id`, `user_id`, `session_id` |
| `AgentToolsProvider` | `tools` |
| `TodoListPromptContextProvider` | `todos`, `todo_list_md` |

Use `snake_case` keys, prefer simple types (strings, numbers, lists, maps), and
put `null` for absent values so `{% if x %}` blocks behave intuitively.

## Sections the runtime appends

After the agent's template and the memory strategy's addendum, the runtime
appends sections of its own. They are rendered fresh on every round, so a
change shows up on the next turn without touching the agent.

| Section | When | What it says |
|---------|------|--------------|
| `## Working directory` | the session has one, or has additional directories | Where relative paths resolve, where `bash` runs, which further directories may be reached by absolute path |
| `## User instructions` | the user's instructions directory holds one of the files below | The user's standing instructions, verbatim |
| `## Project instructions` | the working directory holds one of them | The project's own instructions, verbatim |
| `## Attached files` | files are attached to the chat | Their names, kinds and on-disk paths, and how to search them |

### Instruction files

Standing instructions are written once in a file instead of in every
message. They are read from two places:

- **the user's**, in the directory named by
  `mindconnect.agent.instructions.user-dir`. Holds in every project.
- **the project's**, in the session's working directory. Holds while the
  session works there, and comes after the user's in the prompt, so the more
  specific one has the last word.

In both places the first of these file names that exists is read:

1. **`AGENTS.md`** — an open specification since 2025, stewarded by the Linux
   Foundation's Agentic AI Foundation and read by a couple of dozen coding
   tools. A repository that already has one needs nothing new here.
2. **`PROMPT.md`** — for a project that wants a file of its own.
3. **`CLAUDE.md`** — so a repository set up for Claude Code is not left silent.

Only one of them is read per place, not all three: a repository carrying two
usually says the same thing twice. For the project, only the working directory
itself is searched, not its parents — that directory is the one the user picked
with the chat's folder button, so reading from it is something they asked for.
Content beyond 20,000 characters is cut, with a line saying so.

#### One directory, or one per user

`mindconnect.agent.instructions.user-dir` decides which. Unset it means
`~/.mindconnect`, beside what the launcher keeps there, which is what a
desktop wants: one person, one home.

A server runs as a single service account, so that same path would be one
file for everybody. Put `{user}` in the value and each user gets a directory
of their own:

```yaml
mindconnect:
  agent:
    instructions:
      user-dir: /srv/mindconnect/users/{user}
```

The user id fills the placeholder and may name one directory only: an id
carrying a separator or `..` is refused rather than allowed to climb out.
`off` as the value drops the user scope, leaving only the project's file.

## Adding your own variable

Contribute a provider and your variable is available to every agent's prompt:

```java
@Component
public class GreetingProvider implements PromptContextProvider {
    @Override
    public void contribute(Map<String, Object> ctx, AgentDefinition def,
                           AgentSession session, AuthenticationInfo auth) {
        ctx.put("greeting", "Welcome back");
    }
}
```

```text
{{ greeting }}, {{ agent_name }}.
```

Expensive providers (database or file-system reads) can return a higher
`priority()` so they only do work when the prompt actually references their
variable.

## Ad-hoc variables

Some call sites inject extra variables for a single render — for example a
response-reviewer sub-agent receives the user's message and the agent's draft
answer. These are passed as an `extraVars` map to `render(...)` and override any
value a provider produced for the same key.
