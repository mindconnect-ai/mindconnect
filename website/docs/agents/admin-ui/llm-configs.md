---
title: LLM Configs
sidebar_position: 2
---

# LLM Configs

The **LLM Configs** section manages the model configurations agents use. For the
list of bundled configs and every field, see the
[LLM configuration reference](../llm-configs-reference.md).

## Create, edit, delete

- **New LLM Config** — add a provider/model configuration.
- **Edit** — change fields on an existing config.
- **Delete** — remove a config.

A config has a `name`, `provider`, `model`, `baseUrl`, `apiKey`,
`contextWindowTokens`, and optional `additionalParams`, rate-limit and retry
settings. The form also covers:

- **Type** — `Chat` or `Embedding` (embedding configs power the vector store);
- **alias mode** — *Delegates To* forwards the config to another one by name;
- **Temperature** and **Max Output Tokens**;
- provider-specific extra parameters, rendered from the provider catalog;
- an **Encrypt** button next to the API-key field for literal keys.

## Testing a config

Each config's detail view has a **Test** button. It opens a dialog where you
type a test message (for an `Embedding` config the button reads **Embed** and
takes a text to embed), sends a real request to the provider
(`POST /admin/api/llm-configs/{id}/test`) and shows the outcome:

- ✅ **OK** — credentials and endpoint work; the model replied. The result also
  reports duration, token counts and finish reason.
- ❌ **Error** — shows the failure (bad key, wrong endpoint, model not found,
  provider unreachable…).

Test a cloud config right after creating it, to confirm the API key and
`baseUrl` before wiring it into an agent.

## API keys via environment variables {#api-keys}

You don't put raw secrets into a config. Instead the `apiKey` (and `model`,
`baseUrl`, `name`) fields support **environment-variable placeholders**:

```json
{
  "name": "claude-default",
  "provider": "ANTHROPIC",
  "model": "${CLAUDE_MODEL:claude-sonnet-4-6}",
  "apiKey": "${ANTHROPIC_API_KEY}"
}
```

How it works:

- **`${VAR}`** is replaced with the value of environment variable `VAR`.
- **`${VAR:default}`** uses `VAR` if set, otherwise the `default` after the
  colon (handy for picking a model id).
- Resolution happens **at use time** (when a gateway makes the call), *not* when
  the config is saved — so the **raw `${...}` placeholder stays in storage** and
  no secret is ever written to disk in the config.
- If you do enter a literal API key, it is stored **encrypted**, not in clear
  text.

So to use Anthropic, you set the config's `apiKey` to `${ANTHROPIC_API_KEY}`
(the bundled `claude-default` already does this) and export the variable before
starting the app:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Local providers (`lm-studio-default`, `agent-default`) need no key.

👉 The full list of variables you can set is on the
**[Environment variables](../environment-variables.md)** page.
