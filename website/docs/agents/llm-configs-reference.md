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
| `gemma-reader` | LM Studio (local) | `google/gemma-4-e4b` | none — a small local model for page-reading sub-agents, `GEMMA_MODEL` to override |
| `agent-default` | alias → `openai-default` | — | whatever the target needs; the default every bundled agent uses |
| `embeddings` | LM Studio (local), `EMBEDDING` | `text-embedding-nomic-embed-text-v1.5` | none — `EMBEDDING_BASE_URL`, `EMBEDDING_MODEL` to override |
| `openai-embeddings` | OpenAI, `EMBEDDING` | `text-embedding-3-small` | `OPENAI_API_KEY`, `OPENAI_EMBEDDING_MODEL` |

:::info `agent-default` is the default
Every bundled agent references **`agent-default`**. It is an alias, not a
provider config of its own: out of the box it delegates to `openai-default`,
so the agents work as soon as `OPENAI_API_KEY` is set. To move all of them to
another provider at once, repoint the alias — edit `delegatesTo` in
`agent-default.json` or in the Admin UI (for example to `claude-default`, or
to `lm-studio-default` for a fully local setup). To move a single agent,
change its `llmConfigName` instead.
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
  "capabilities": ["TOOL_CALLING", "VISION", "DOCUMENTS"],
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
  },
  "fallbackModels": ["openai-default", "gemini-default"]
}
```

| Field | Meaning |
|-------|---------|
| `provider` | One of `ANTHROPIC`, `OPENAI`, `AZURE_OPENAI`, `GOOGLE_GEMINI`, `LM_STUDIO` |
| `model` | Model id. `${VAR:default}` reads an env var with a fallback |
| `baseUrl` | API endpoint (override for proxies / local servers) |
| `apiKey` | API key, usually injected from an env var |
| `contextWindowTokens` | Token budget used to size the working-memory window |
| `capabilities` | What the model reads and does — `TOOL_CALLING`, `VISION`, `DOCUMENTS`, `AUDIO_INPUT`. Vision and documents decide whether an image or PDF reaches the model as content or as a placeholder. Omitted: the provider's default applies |
| `additionalParams` | Provider-specific options (e.g. Anthropic `thinking` / `effort`) |
| `rateLimit` | Optional — cap concurrent requests |
| `retry` | Optional — automatic retry with backoff |
| `fallbackModels` | Optional — other configs, by name, to switch to when this one is rate-limited (see [rate limits and fallbacks](./llm-gateway.md#rate-limits-and-fallbacks)) |

## Setting API keys

Export the relevant variable before starting the Admin UI or CLI. For example:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
# or
export OPENAI_API_KEY=sk-...
```

Local providers (`lm-studio-default`, `gemma-reader`, `embeddings`) need no
key — just a running LM Studio instance. `agent-default` needs whatever its
target needs: `OPENAI_API_KEY` as shipped.

## Adding your own

Drop a new JSON file into `initial-data/llm-configs/`, give it a unique `name`,
and reference that name from an agent's `llmConfigName`. In the Admin UI you can
also create and edit configs from the **LLM Configs** screen.
