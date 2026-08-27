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
| `AgentMetadataProvider` | `agent_name`, `agent_id`, `namespace`, `user_id`, `session_id` |
| `AgentToolsProvider` | `tools` |
| `WorkspaceNotesProvider` | `user_notes`, `user_profile` |
| `TodoListPromptContextProvider` | `todos`, `todo_list_md` |

Use `snake_case` keys, prefer simple types (strings, numbers, lists, maps), and
put `null` for absent values so `{% if x %}` blocks behave intuitively.

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
