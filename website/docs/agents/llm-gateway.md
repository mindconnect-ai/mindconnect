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
- **`LlmTranscription`** (in-port) — speech to text, since 0.5.3: name a
  config, hand over one recording, get one transcript. Routing works as it
  does for chat, so an alias is followed.
- **`TranscriptionGateway`** (out-port) — what a provider's speech-to-text
  adapter implements. `OpenAiTranscriptionGateway` calls the
  OpenAI-compatible `/v1/audio/transcriptions` endpoint, which OpenAI, Groq
  and a local Whisper server all speak.
- **`LlmGateway`** (out-port) — what each provider adapter implements.
- **`LlmConfigRepository`** (out-port) — where configs are stored;
  `LlmCallListener` lets you observe every call (wire traces).

Between them sits the routing layer:

```
caller → LlmChat (RoutingLlmChatService) → LlmGatewayRegistry → LlmGateway adapter → provider
caller → LlmTranscription (RoutingLlmTranscriptionService) → TranscriptionGateway adapter → provider
```

`RoutingLlmChatService` looks up the agent's `llmConfigName`, finds the matching
`LlmConfig` (provider, model, endpoint, credentials, default params), and asks
the `LlmGatewayRegistry` for the gateway registered against that provider. Every
call is streamed — chunks arrive in order and end with a single `Done`.

## Rate limits and fallbacks

A provider answering **HTTP 429** (rate limit) or **529** (overloaded) is
handled in three steps, each configured on the [LLM config](./llm-config-json.md)
itself:

1. **Stay under the limit** — `rateLimit.maxConcurrentRequests` caps how many
   calls for that config are in flight at once, which is what keeps a
   `run_agents` fan-out from producing the 429 in the first place.
2. **Wait it out** — the `retry` block retries with exponential backoff, and a
   `Retry-After` header from the provider always wins over the computed wait.
3. **Move on** — `fallbackModels` names other configs, in order. When the
   retries are used up, the same request is re-routed at the next one until a
   model answers:

   ```json title="llm-configs/claude-default.json"
   "fallbackModels": ["openai-default", "gemini-default"]
   ```

   A fallback is usually a config at a *different provider* — its rate limit is
   a different limit — but it can be any config, including an alias. Names that
   match no config are skipped, and a list that circles back to the primary
   never calls it twice. With no fallbacks named, the rate-limit error reaches
   the caller, which is the behaviour of every version before this one.

A fallback is only taken **before anything has streamed**. Once the first chunk
has reached the caller, switching models would duplicate a partial answer, so
the error propagates instead. Gateways report 429/529 before any content, so in
practice the whole chain happens before the user sees a single token.

### What a rate limit looks like in the log

Every 429/529 is logged with the config, the model, the provider's
`Retry-After` hint, what happens next, and the provider's own error body — so
the log says which of a dozen models was limited and whether anything is
configured to deal with it:

```
WARN  LLM RATE LIMIT (HTTP 429) from Anthropic — config 'claude-default',
      model 'claude-sonnet-4-6', Retry-After 20 s; retrying up to 4 attempt(s),
      then falling back to openai-default → gemini-default
      — provider said: {"type":"error","error":{"type":"rate_limit_error",…}}
WARN  LLM rate limit (HTTP 429) for config 'claude-default'
      (model claude-sonnet-4-6), attempt 1/4 — retrying in 20000 ms (provider Retry-After)
WARN  LLM rate limit HTTP 429 on config 'claude-default' (model claude-sonnet-4-6)
      — falling back to 'openai-default' (model gpt-5.4-mini), fallback 1 of 2
INFO  LLM fallback succeeded: config 'openai-default' (model gpt-5.4-mini)
      answered for rate-limited 'claude-default'
```

`no fallback models configured` in that first line is the part worth grepping
for: it is the case an admin can fix from the Admin UI.

## Implementations

### Provider gateways

| Gateway | Providers it serves |
|---------|---------------------|
| `ClaudeGateway` | Anthropic (Claude) |
| `OpenAiCompatibleGateway` | OpenAI and every OpenAI-compatible API — LM Studio, Groq, Ollama, Mistral, DeepSeek, xAI (Grok), Moonshot (Kimi), Together, OpenRouter, Perplexity, Fireworks |
| `AzureOpenAiGateway` | Azure OpenAI |
| `GeminiGateway` | Google Gemini |

The `LlmProvider` enum lists all wired providers: `OPENAI`, `ANTHROPIC`,
`AZURE_OPENAI`, `GOOGLE_GEMINI`, `GROQ`, `OLLAMA`, `MISTRAL`, `DEEPSEEK`,
`XAI` (Grok), `MOONSHOT` (Kimi), `TOGETHER`, `OPENROUTER`, `PERPLEXITY`,
`FIREWORKS`, `LM_STUDIO`. Each constant also carries the provider's own
endpoint, so a config may leave `baseUrl` out. Everything but Anthropic, Azure
OpenAI and Gemini is served by the OpenAI-compatible gateway, which the apps
register as the default for the whole enum — so another compatible vendor is
one enum constant, not new wiring.

### Decorator gateways

Two `LlmGateway` implementations wrap another gateway to add cross-cutting
behaviour without the provider adapters knowing about it:

- **`RetryingLlmGateway`** — retries transient failures (per the **LLM
  config's** `retry` block; no block means no retry).
- **`ThrottlingLlmGateway`** — enforces rate limits (per the LLM config's
  `rateLimit` block).

Switching to another model is *not* a decorator: a fallback means a different
config, possibly at a different provider, so it lives one level up in
`RoutingLlmChatService`, which is the layer that maps a name to a config and a
gateway.

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
