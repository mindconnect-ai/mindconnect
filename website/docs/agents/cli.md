---
title: CLI client
sidebar_position: 7
---

# CLI client

`mc-agent-cli` is a terminal chat client for the agents. By default it runs
**locally** — the agent runtime is embedded in the CLI process, so you don't
need a separate server. It can also connect to a remote agent server.

## Run it

```bash
mvn -f agents/client/mc-agent-cli/pom.xml spring-boot:run
```

On start it shows whether it's in **local** or **remote** mode, lets you pick
an agent, and resumes or starts a session. It remembers where you were (agent
and session) and jumps straight back in on the next start; in the session
picker, `/delete <n>` removes a stored session.

## Environment variables

The CLI reads the same [environment variables](./environment-variables.md) as
the rest of the agents area — export them in your shell before starting:

```bash
export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY / GEMINI_API_KEY / …
export TAVILY_API_KEY=tvly-...        # only if you use the web_search tool
```

:::note No encryption key needed
Unlike the Admin UI, the CLI does **not** require
`MINDCONNECT_ENCRYPTION_SECRET_KEY` — it reads provider API keys straight from
the environment and stores nothing encrypted.
:::

## Local vs. remote mode

By default the CLI runs in **local mode** (embedded runtime). To talk to a
running agent server instead, set the remote URL in
`agents/client/mc-agent-cli/src/main/resources/application.yaml`:

```yaml
mindconnect:
  remote:
    url: http://localhost:8080
```

## Using the CLI

Once you're in a session, just type a message and press Enter to send it to the
current agent. Everything starting with `/` is a command.

### Conversation

| Command | What it does |
|---------|--------------|
| `<message>` | Send a message to the current agent |
| `/history` | Show the conversation (chat messages only) |
| `/history <n>` | Show only the last `<n>` chat pairs |
| `/memory` | Show full context: system prompt + current window + token usage |
| `/compress` | Compress all messages into a summary, clear working memory |

### Tools & inspection

| Command | What it does |
|---------|--------------|
| `/tools` | List the tools available to this agent |
| `/tool-calls` | Tool calls for the current turn (since your last message) |
| `/tool-calls all` | All tool calls in this session |
| `/tool-calls <n>` | The last `<n>` tool calls |
| `/messages` | Messages for the current turn |
| `/messages all` | All messages in this session |
| `/messages <n>` | The last `<n>` messages |
| `/message <seq>` | Full details of a message by sequence number |

### Managing messages

| Command | What it does |
|---------|--------------|
| `/delete-message <seq>` | Delete a single message |
| `/delete-message <from>-<to>` | Delete a sequence range (inclusive) |
| `/delete-message <from>-last` | Delete from `<from>` to the end |
| `/delete-message last-<n>` | Delete the last `<n>` messages |

### Sessions & navigation

| Command | What it does |
|---------|--------------|
| `/new` | Start a new session (keep the same agent) |
| `/sessions` | List and switch to another session |
| `/back` | Return to agent selection |
| `/help` | Show the command help |
| `/quit` (or `/exit`) | Exit the CLI |

## Where data lives

In local mode the CLI persists under one root, `mindconnect.data.base-dir`
(default `data`):

- `data/system/agents` — agent definitions
- `data/system/llm-configs` — LLM configs
- `data/conversations/<conversationId>/` — conversation history, incl. `traces/`
- `data/users/<userId>/sessions/<sessionId>/workspace` — workspace files

The user id defaults to `mindconnect.user.id` from `application.yaml` — override
it to keep data per user.

## When to use the CLI vs. the Admin UI

- **CLI** — fast, scriptable, great for development and trying agents locally.
- **[Admin UI](./admin-ui/index.md)** — visual: configure agents, inspect working
  memory, traces, todos and the workspace.
