---
title: LLM configuration reference
sidebar_position: 8
---

# LLM configuration reference

An **LLM config** tells an agent which model to call and how to authenticate.
Each agent references one by name (`llmConfigName`). The same configs ship with
both the Admin UI and the CLI, under
`src/main/resources/initial-data/llm-configs/`.

You select a model per agent by setting its `llmConfigName` to one of these.

:::tip
For *managing* configs in the Admin UI (create / edit / test, and how API keys
resolve from environment variables), see
[Admin UI → LLM Configs](./admin-ui/llm-configs.md).
:::

## Supported providers

| Config | Provider | Default model | Auth / endpoint env vars |
|--------|----------|---------------|--------------------------|
| `claude-default` | Anthropic | `claude-sonnet-4-6` | `ANTHROPIC_API_KEY` |
| `claude-haiku-default` | Anthropic | `claude-haiku-4-5` | `ANTHROPIC_API_KEY` |
| `openai-default` | OpenAI | `gpt-5.4-mini` | `OPENAI_API_KEY` |
| `azure-openai-default` | Azure OpenAI | deployment `gpt-4o` | `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_DEPLOYMENT` |
| `gemini-default` | Google Gemini | `gemini-2.0-flash` | `GEMINI_API_KEY` |
| `lm-studio-default` | LM Studio (local) | `openai/gpt-oss-120b` | none — local server at `http://localhost:1234` |
| `agent-default` | LM Studio (local) | `openai/gpt-oss-120b` | none — the default most agents use |

:::info `agent-default` is the default
Most bundled agents reference **`agent-default`**, which points at a local
**LM Studio** server. That means the agents run fully locally out of the box —
no API key required — as long as LM Studio is serving a model on
`http://localhost:1234`. Switch an agent to a cloud provider by changing its
`llmConfigName` (e.g. to `claude-default`) and setting the matching API key.
:::

## Anatomy of a config

```json title="llm-configs/claude-default.json"
{
  "name": "claude-default",
  "provider": "ANTHROPIC",
  "model": "${CLAUDE_MODEL:claude-sonnet-4-6}",
  "baseUrl": "https://api.anthropic.com",
  "apiKey": "${ANTHROPIC_API_KEY}",
  "contextWindowTokens": 200000,
  "additionalParams": {
    "thinking": "adaptive",
    "effort": "high"
  },
  "rateLimit": { "maxConcurrentRequests": 3 },
  "retry": {
    "enabled": true,
    "maxAttempts": 5,
    "baseBackoffMillis": 2000,
    "maxBackoffMillis": 30000
  }
}
```

| Field | Meaning |
|-------|---------|
| `provider` | One of `ANTHROPIC`, `OPENAI`, `AZURE_OPENAI`, `GOOGLE_GEMINI`, `LM_STUDIO` |
| `model` | Model id. `${VAR:default}` reads an env var with a fallback |
| `baseUrl` | API endpoint (override for proxies / local servers) |
| `apiKey` | API key, usually injected from an env var |
| `contextWindowTokens` | Token budget used to size the working-memory window |
| `additionalParams` | Provider-specific options (e.g. Anthropic `thinking` / `effort`) |
| `rateLimit` | Optional — cap concurrent requests |
| `retry` | Optional — automatic retry with backoff |

## Setting API keys

Export the relevant variable before starting the Admin UI or CLI. For example:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
# or
export OPENAI_API_KEY=sk-...
```

Local providers (`lm-studio-default`, `agent-default`) need no key — just a
running LM Studio instance.

## Adding your own

Drop a new JSON file into `initial-data/llm-configs/`, give it a unique `name`,
and reference that name from an agent's `llmConfigName`. In the Admin UI you can
also create and edit configs from the **LLM Configs** screen.
