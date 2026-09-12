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
- **Fallback models (on rate limit)** (chat configs) — other configs, picked
  from the list, that a rate-limited call moves on to in order: when the
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
