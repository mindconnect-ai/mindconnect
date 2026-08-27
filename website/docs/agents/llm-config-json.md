---
title: LLM config JSON
sidebar_position: 13
---

# LLM config JSON

The format of a file in
[`initial-data/llm-configs/`](./initial-data.md). One file per config.

## Example

```json title="llm-configs/claude-default.json"
{
  "id": "00000001-0000-0000-0000-000000000002",
  "name": "claude-default",
  "provider": "ANTHROPIC",
  "model": "${CLAUDE_MODEL:claude-sonnet-4-6}",
  "baseUrl": "https://api.anthropic.com",
  "apiKey": "${ANTHROPIC_API_KEY}",
  "defaultTemperature": 0.7,
  "maxOutputTokens": 8192,
  "additionalParams": {
    "thinking": "adaptive",
    "effort": "high"
  },
  "contextWindowTokens": 200000,
  "rateLimit": {
    "maxConcurrentRequests": 3
  },
  "retry": {
    "enabled": true,
    "maxAttempts": 5,
    "baseBackoffMillis": 2000,
    "maxBackoffMillis": 30000
  }
}
```

A minimal local config (no key needed):

```json title="llm-configs/agent-default.json"
{
  "id": "00000001-0000-0000-0000-000000000000",
  "name": "agent-default",
  "provider": "LM_STUDIO",
  "model": "openai/gpt-oss-120b",
  "baseUrl": "http://localhost:1234",
  "apiKey": "lm-studio",
  "defaultTemperature": 0.7,
  "maxOutputTokens": 8192,
  "additionalParams": {},
  "contextWindowTokens": 131072
}
```

## Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Stable identifier. Use a fresh UUID for a new config. |
| `name` | string | Unique name; what an agent references via `llmConfigName`. |
| `provider` | enum | One of `ANTHROPIC`, `OPENAI`, `AZURE_OPENAI`, `GOOGLE_GEMINI`, `LM_STUDIO`, `GROQ`, `OLLAMA`, `MISTRAL`, `DEEPSEEK`, `TOGETHER`, `OPENROUTER`, `PERPLEXITY`, `FIREWORKS`. |
| `model` | string | Model id. Supports `${VAR}` / `${VAR:default}`. |
| `baseUrl` | string | API endpoint (override for proxies / local servers). |
| `apiKey` | string | API key — almost always an env-var placeholder like `${ANTHROPIC_API_KEY}`. Literal keys are stored encrypted in the Admin UI (the CLI stores them as-is — use placeholders there). |
| `type` | enum? | `CHAT` (default) or `EMBEDDING` — embedding configs power the vector store. |
| `defaultTemperature` | number | Sampling temperature (e.g. `0.7`). |
| `maxOutputTokens` | int | Max tokens the model may generate per response. |
| `contextWindowTokens` | int | Token budget used to size the working-memory window. |
| `additionalParams` | object | Provider-specific options. For Anthropic e.g. `thinking` (`adaptive`/`disabled`) and `effort` (`low`, `medium`, `high`, `xhigh`, `max`). |
| `rateLimit` | object? | Optional. `maxConcurrentRequests` caps in-flight calls. |
| `retry` | object? | Optional. `enabled`, `maxAttempts`, `baseBackoffMillis`, `maxBackoffMillis`. **Omitting the block means no retry at all** — the built-in defaults only apply when the block is present and enabled. |
| `isAlias` / `delegatesTo` | bool / string | Alias configs forward to another config by name — useful to give a stable name (`agent-default`) that you can repoint. |

:::info Env-var placeholders
`name`, `model`, `baseUrl` and `apiKey` support `${VAR}` / `${VAR:default}`,
resolved **at use time** — the raw placeholder stays in storage. See
[how API keys resolve](./admin-ui/llm-configs.md#api-keys) and the
[environment variables](./environment-variables.md) page.
:::

See also the [LLM configuration reference](./llm-configs-reference.md) for the
bundled configs and supported providers.
