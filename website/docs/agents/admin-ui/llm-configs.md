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
- **Pricing** — what the model costs, per period; see [Pricing](#pricing).

A config has a `name`, `provider`, `model`, `baseUrl`, `apiKey`,
`contextWindowTokens`, and optional `additionalParams`, rate-limit and retry
settings.

Picking the **provider** fills the **Base URL** in with that provider's own
endpoint — `https://api.mistral.ai` for Mistral, `https://api.groq.com/openai`
for Groq, `http://localhost:11434` for Ollama — so nobody has to look it up.
Switching provider replaces a URL that is still the previous provider's
default; a URL you typed yourself (a proxy, a compatible host) is left alone.
Azure OpenAI is the exception: its endpoint is your own resource, so the field
stays empty for you to fill in.

The **Model** field is then a dropdown of the models that endpoint actually
serves, read live: LM Studio through its native API, every other provider
through its `/v1/models` listing (Anthropic and Gemini through their
equivalents). Only the models that fit the config type are offered — image,
text-to-speech, moderation, realtime and legacy completion models, which no
config can call, are left out — and a model the provider no longer lists stays
selectable so that opening the form never drops it. The listing needs the API
key (except at OpenRouter, whose catalog is public, and at local servers), so
entering one reloads the list — as does changing the base URL. When a provider cannot be asked, the field falls
back to free text with the reason in its hint ("the API key is not accepted",
"this endpoint serves no model list"), so typing an id always remains possible.
Azure OpenAI stays free text: its configs name a *deployment*, which is your
own name for a model.

The form also covers:

- **Type** — `Chat`, `Embedding` (embedding configs power the vector store)
  or `Speech to text` (since 0.5.3: a Whisper-style model that turns a
  recording into text). Only chat configs have sampling settings; a
  speech-to-text config takes its language, prompt and response format
  from the provider parameters;
- **Capabilities** (chat configs) — what the model reads and does: tool
  calling, vision (images), documents (PDF), audio input. Vision and documents
  steer the runtime: an image or PDF sent with a message reaches the model as
  a picture or document when the config has the capability, as a placeholder
  line when it does not. A config that has never declared any shows its
  provider's default preselected; saving the form pins that set;
- **Fallback models (on rate limit)** (chat configs) — other chat configs
  (or aliases of one, shown with their target), picked from the list, that a
  rate-limited call moves on to in order: when the
  provider answers 429 (or 529, overloaded) and the retries are used up, the
  same request is re-sent through the next one. Pick a config at another
  provider — its limit is a different limit. Nothing picked means the call
  fails with the rate-limit error. See
  [rate limits and fallbacks](../llm-gateway.md#rate-limits-and-fallbacks);
- **alias mode** — *Delegates To* forwards the config to another one by name;
- **Temperature** and **Max Output Tokens**;
- provider-specific extra parameters, rendered from the provider catalog;
- an **Encrypt** button next to the API-key field for literal keys.

An alias works for a speech-to-text config like for any other: set
*Delegates To* and every caller naming the alias lands on the config behind
it, the Test dialog included — it asks for a recording when the config at the
end of the chain is a speech one.

A fresh installation is seeded with a `speech-to-text` config: OpenAI's
`whisper-1` behind `${OPENAI_API_KEY}`. Point `SPEECH_TO_TEXT_BASE_URL` at
Groq or a local Whisper server to use it without an OpenAI key, and
`SPEECH_TO_TEXT_MODEL` at another model — the seed pins no response format,
so switching the model cannot collide with one.

## Pricing {#pricing}

Under a config's fields — on its detail view and on its edit form — sits a
**Pricing** section: what the model costs, one row per **price period**,
because rates change. Each period has

- **Valid from** — the first day (UTC) the price applies, inclusive;
- **Valid to** — the first day it no longer applies, exclusive. Empty means
  *until further notice*, so the current price usually has none; when the rate
  changes, give the old period an end on the day the new one starts;
- **Currency** — a three-letter ISO code, `USD` unless you say otherwise;
- **Input**, **Output** and **Cached input per 1M tokens** — decimal rates per
  million tokens. Cached input is input the provider read from its prompt
  cache (OpenAI reports it on its own, Anthropic when prompt caching is used);
  leave it empty and cached input is priced as input.

**Add price…** and each row's **Edit** open a small dialog of their own, and
**Remove** deletes a period. The section is a separate form with its own
endpoints (`/admin/api/llm-configs/{id}/prices…`): a price is its own entity,
not a field of the config, so saving the config form never touches it. The
table marks the period valid today as *current*, the others as *past* or
*upcoming*.

Two rules are checked on save, and a refused save keeps the dialog open with
the reason:

- the periods of one config **must not overlap** — the message names the
  period that is in the way;
- an **alias has no prices**: its calls are served by the config it points at
  and cost what that one costs, so an alias's section just says *Priced by
  &lt;target&gt;*.

A price names its config. **Renaming** the config in the form takes its prices
along; **deleting** it deletes them, so a new config of the same name starts
unpriced. A config removed another way (the REST API, a file deleted by hand)
leaves its prices behind — harmless, since nothing asks for that name, and
visible again on a new config of that name.

The prices are stored per namespace like the configs: a file per period under
`<data>/<namespace>/system/llm-prices/`, or the table `mc_llm_price` on
Postgres. Nothing in the open-source runtime charges anything with them; they
are there for whoever reports on usage. The port, `LlmPriceRepository`, offers
`priceAt(configName, instant)` — the period valid on that UTC day — and
`LlmPrice.cost(inputTokens, cachedInputTokens, outputTokens)` does the
arithmetic in `BigDecimal`, so a report prices each call with the rate of its
own day and a corrected price applies to past calls too.

## Dictating in the chat {#dictation}

The chat composer has a microphone next to the **+**. Press it, speak, press it
again: while it records, the mic turns red and the input's placeholder counts
the seconds; on stop the recording goes to the server, and the transcript lands
in the input — after whatever was already typed, so dictating adds to a draft
rather than replacing it. Nothing is sent on its own; the words are read,
corrected and sent by the person who spoke them.

The chat always asks for the config named **`speech-to-text`**. That is a name,
not a model: point an alias of that name at whichever config should serve the
chat, and swap what is behind it without touching anything else. Without such a
config the mic answers with a toast saying which config to create.

Recording needs a microphone and a **secure context**, which is a browser rule,
not a setting of this application: the microphone is handed out on HTTPS pages
and on `localhost`, and nowhere else. A deployment behind TLS — a reverse proxy
with a certificate, an ingress, Cloud Run — is therefore fine; the same server
reached over plain `http://` on a host name is not, and the placeholder then
says the page is served over plain HTTP. Typing and file upload are unaffected
either way.

## LM Studio: pick a model instead of typing it {#lm-studio}

A new config starts with no provider selected; the field is required. With
**LM Studio** as the provider the form asks the server at the base URL
what it has installed, and the model field becomes a dropdown:

- the list comes from LM Studio's native REST API (`GET {baseUrl}/api/v0/models`,
  falling back to `/api/v1/models` on newer versions) and is filtered by the
  config's type — chat models (`llm`, `vlm`) for a chat config, embedding
  models for an embedding config. LM Studio serves no transcription models,
  so a speech-to-text config finds none there and points at OpenAI, Groq or a
  local Whisper server instead;
- each entry shows the model's context length and whether it is loaded, e.g.
  `openai/gpt-oss-120b · 32k loaded (max 128k) · tools`;
- picking a model fills in **Context Window Tokens** with the length the model
  is *loaded* with (that is the limit requests hit), or its maximum when it is
  not loaded yet — LM Studio may then load it with a smaller context, so check
  the value after the first call. The **Capabilities** preselection follows
  what LM Studio reports (tool use, vision). Both stay editable;
- a config that has no name yet is named after the picked model — the last
  path segment, so `openai/gpt-oss-120b` becomes `gpt-oss-120b`;
- the base URL defaults to `http://localhost:1234`; changing it reloads the
  list, so a second LM Studio on another machine works the same way;
- a model the config names but the server no longer has stays selectable,
  marked *not installed in LM Studio*;
- there is no API-key field — a local LM Studio takes no key.

When LM Studio is not running (or the URL points elsewhere) the model field
stays a text field and its hint says what went wrong; type the model id as
before, or start LM Studio and re-enter the base URL to load the list.

## Testing a config

Each config's detail view has a **Test** button. It opens a dialog where you
type a test message (for an `Embedding` config the button reads **Embed** and
takes a text to embed), sends a real request to the provider
(`POST /admin/api/llm-configs/{id}/test`) and shows the outcome:

- ✅ **OK** — credentials and endpoint work; the model replied. The result also
  reports duration, token counts and finish reason.
- ❌ **Error** — shows the failure (bad key, wrong endpoint, model not found,
  provider unreachable…).

A `Speech to text` config is tested with a recording instead of a message: the
dialog shows a drop zone, and dropping or picking an audio file (WebM, WAV,
MP3, M4A, OGG, FLAC) posts it to `POST /admin/api/llm-configs/{id}/test-audio`.
The transcript comes back in the same dialog, with the detected language and
the length of the recording when the model reports them.

The dialog can also record: **Record** asks the browser for the microphone and
counts the seconds, **Stop** sends what was spoken to the same endpoint. It is
browser-only — the recording is made and posted by the page, the server sees an
ordinary upload. The microphone needs a secure context, so this works on
`localhost` and over HTTPS; elsewhere the status line says the browser refused,
and the drop zone still does the job.

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
- On a server several people share, the `apiKey` is looked up in **your own
  variables** first (profile page → *Your variables*), then the **namespace's**
  (set by its creator), then the process environment — so everyone can bring
  their own key. The other fields skip your own variables: `model` and `baseUrl`
  resolve from the namespace and the process alone. See
  [Environment variables](../environment-variables.md#your-own-and-your-namespaces-variables).

So to use Anthropic, you set the config's `apiKey` to `${ANTHROPIC_API_KEY}`
(the bundled `claude-default` already does this) and export the variable before
starting the app:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Local providers (`lm-studio-default`, `gemma-reader`, `embeddings`) need no
key. `agent-default`, which every bundled agent uses, is an alias for
`openai-default` and therefore needs `OPENAI_API_KEY` — or repoint it.

👉 The full list of variables you can set is on the
**[Environment variables](../environment-variables.md)** page.
