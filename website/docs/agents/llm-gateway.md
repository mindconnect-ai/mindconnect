---
title: LLM gateway
sidebar_position: 4
---

# LLM gateway

The LLM gateway (`mc-llm-gateway`) is the layer that turns a provider-agnostic
chat request into a streamed completion from a concrete LLM provider. The rest
of the runtime never talks to OpenAI or Anthropic directly — it depends on the
gateway's ports, and the gateway routes each call to the right provider based on
configuration.

## What it is

It is a **library** (a plain `jar`), not a server. The main ports:

- **`LlmChat`** (in-port) — what callers use to stream a chat completion.
- **`LlmEmbeddings`** (in-port) — embeddings, used by the vector store.
- **`LlmGateway`** (out-port) — what each provider adapter implements.
- **`LlmConfigRepository`** (out-port) — where configs are stored;
  `LlmCallListener` lets you observe every call (wire traces).

Between them sits the routing layer:

```
caller → LlmChat (RoutingLlmChatService) → LlmGatewayRegistry → LlmGateway adapter → provider
```

`RoutingLlmChatService` looks up the agent's `llmConfigName`, finds the matching
`LlmConfig` (provider, model, endpoint, credentials, default params), and asks
the `LlmGatewayRegistry` for the gateway registered against that provider. Every
call is streamed — chunks arrive in order and end with a single `Done`.

## Implementations

### Provider gateways

| Gateway | Providers it serves |
|---------|---------------------|
| `ClaudeGateway` | Anthropic (Claude) |
| `OpenAiCompatibleGateway` | OpenAI and every OpenAI-compatible API — LM Studio, Groq, Ollama, Mistral, DeepSeek, Together, OpenRouter, Perplexity, Fireworks |
| `AzureOpenAiGateway` | Azure OpenAI |
| `GeminiGateway` | Google Gemini |

The `LlmProvider` enum lists all wired providers: `OPENAI`, `ANTHROPIC`,
`AZURE_OPENAI`, `GOOGLE_GEMINI`, `GROQ`, `OLLAMA`, `MISTRAL`, `DEEPSEEK`,
`TOGETHER`, `OPENROUTER`, `PERPLEXITY`, `FIREWORKS`, `LM_STUDIO`. Most of them
share the OpenAI-compatible gateway — adding another compatible vendor is a
config entry, not new code.

### Decorator gateways

Two `LlmGateway` implementations wrap another gateway to add cross-cutting
behaviour without the provider adapters knowing about it:

- **`RetryingLlmGateway`** — retries transient failures (per the **LLM
  config's** `retry` block; no block means no retry).
- **`ThrottlingLlmGateway`** — enforces rate limits (per the LLM config's
  `rateLimit` block).

You don't compose them by hand: `DefaultLlmGatewayRegistry` wraps every
registered gateway automatically as `Throttling( Retrying( adapter ) )`.

## Configuration

A gateway is selected per agent through its `llmConfigName`, which points at an
[LLM config](./llm-config-json.md). Switching model or provider — Claude to
GPT to a local LM Studio model — is a config change, never a code change. See
the [LLM configs reference](./llm-configs-reference.md) for every field.

## Running it as a separate server

Today the gateway runs **in-process**: it is a library linked into the agent
server, so an LLM call is a method call, not a network hop.

:::note Not implemented yet
A standalone LLM-gateway server — exposing the same routing, provider adapters,
and retry/throttle behaviour over a network API so several apps could share one
gateway — is **designed but not built**. There is no runnable service yet.
:::

The shape it would take: wrap `RoutingLlmChatService` in a thin REST/SSE app
(the same way `mc-agent-api-app` wraps the runtime), point clients at its URL, and
keep the provider credentials on the gateway server instead of in every app. The
ports already make this a packaging exercise rather than a rewrite — the routing
and adapters wouldn't change.
