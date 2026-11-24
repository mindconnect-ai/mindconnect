---
title: Voice conversations
---

# Voice conversations

`/api/voice` is a WebSocket for talking with an agent: the client sends what
was said as audio, and gets back the transcript, the agent's answer as it is
written and the answer spoken, sentence by sentence. It is the same agent,
session and memory as a typed chat; only the way in and out is spoken.

A server gets the endpoint by depending on `mc-agent-voice-ws`. The module
registers itself, and nothing else is needed. The admin UI app includes it.

```xml
<dependency>
    <groupId>ai.mindconnect</groupId>
    <artifactId>mc-agent-voice-ws</artifactId>
</dependency>
```

## What it needs

Two LLM configs, found by name:

- **`speech-to-text`** turns the recording into text. A fresh installation
  is seeded with one (OpenAI's `whisper-1`).
- **`text-to-speech`** speaks the answer. Also seeded: OpenAI's
  `gpt-4o-mini-tts` with the voice `nova`. A config of type `TEXT_TO_SPEECH`
  takes `voice`, `response_format`, `instructions` (the speaking style) and
  `speed` as provider parameters.

Both names can be aliases. Point them at other configs to change model or
provider for every caller at once. Without a `text-to-speech` config, the
conversation still works, but answers come as text only. The `ready` frame
says which of the two it is.

## Connecting

The handshake is an ordinary request under `/api`, so it is authenticated the
same way: with authentication on, it needs a bearer token (a personal API
token or a Keycloak access token). It names its namespace with
`X-Mindconnect-Namespace`. Without the header it runs in the default (or
brand) namespace, like any other API call.

```text
GET /api/voice
Authorization: Bearer mct_…
X-Mindconnect-Namespace: family
Upgrade: websocket
```

Browsers cannot set headers on a WebSocket. A web page therefore talks to
its own server, which holds the token and opens this connection itself. The
token then never reaches the browser.

## The protocol

Text frames are JSON objects with a `type`; binary frames are audio.

### What the client sends

| Frame | Fields | Meaning |
|---|---|---|
| `start` | `agent`, `sessionId`, `language`, `inputFormat`, `voice`, `message` | Opens the conversation, once per connection. `agent` names the agent (name or id); `sessionId` continues one of the caller's sessions instead. `inputFormat` is the container of the audio to come (`webm`, `wav`, `mp3`, `m4a`, `ogg`). `message` is optional and runs as a first turn right away, e.g. a brief that makes the agent open the conversation. |
| *binary* | the audio | Part of the current utterance. Send pieces while recording, or the whole recording at once. |
| `audio.end` | | The utterance is complete: transcribe it and answer. |
| `text` | `text` | A typed message instead of speech. |
| `cancel` | | Stops the running turn and its speech and drops audio received since the last `audio.end`. Use it when the caller starts talking, or when a recording is discarded. |
| `bye` | | Ends the conversation; the server closes the connection. |

### What the server sends

| Frame | Fields | Meaning |
|---|---|---|
| `ready` | `sessionId`, `speech` | The conversation is open. Keep `sessionId` to continue it later; `speech` says whether answers come as audio. |
| `transcript` | `text` | What was heard in the last utterance. |
| `agent.token` | `text` | A fragment of the answer as it forms. |
| `speech` | `seq`, `text`, `contentType` | One spoken sentence. **The next binary frame is its audio**, a complete file of that type. `seq` counts from 1 within a turn. |
| `agent.done` | `text` | The whole answer. It is the authority over the tokens. |
| `turn.done` | | The turn is over. |
| `error` | `code`, `message` | Something went wrong. `code` is stable: `nothing-heard`, `utterance-too-long`, `turn-failed`, `approval-required`, `bad-request`, `bad-message`, `not-started`, `already-started`. |

A turn runs `transcript` (for audio), then tokens and sentences interleaved,
then `agent.done` and `turn.done`. Speaking starts with the first finished
sentence, while the agent is still writing. A sentence ends at `.`, `!`, `?`,
`:` or `;` followed by a space, or at a line break. A full stop after a digit
does not end a sentence, so "am 24. November" is spoken in one piece.

Markdown and control markers are not read aloud. Emphasis, headings, list
bullets, code ticks and link targets are left out of the spoken text, and so
is anything in double square brackets, such as `[[END]]`. A prompt can
therefore ask the agent for a marker meant for the client, for example "write
`[[END]]` after your goodbye". The listener never hears it, and the client
finds it in `agent.done`.

### Example

```text
→ {"type":"start","agent":"interviewer","language":"de","inputFormat":"webm",
   "message":"Begin the interview with a short greeting and the first question."}
← {"type":"ready","sessionId":"6e43ad53-…","speech":true}
← {"type":"agent.token","text":"Hallo"} …
← {"type":"speech","seq":1,"text":"Hallo, schön, dass du da bist.","contentType":"audio/mpeg"}
← (binary: mp3)
← {"type":"speech","seq":2,"text":"Wie war dein Tag?","contentType":"audio/mpeg"}
← (binary: mp3)
← {"type":"agent.done","text":"Hallo, schön, dass du da bist. Wie war dein Tag?"}
← {"type":"turn.done"}
→ (binary) (binary) (binary)
→ {"type":"audio.end"}
← {"type":"transcript","text":"Ganz gut, ich war im Park."}
← …
```

## Limits and behaviour

- **One turn at a time.** A new utterance or text while a turn is running
  cancels that turn first.
- **No approvals.** A voice conversation cannot answer an approval
  question. An agent that wants to run a tool needing approval ends the turn
  with `approval-required`. Agents meant for voice should not need approvals.
- **Utterance size.** One utterance may carry up to
  `mindconnect.voice.max-utterance-bytes` (10 MiB by default).
- **Sessions are the caller's.** `sessionId` must belong to the
  authenticated user. A session of someone else is refused like an unknown one.

## Settings

| Property | Default | |
|---|---|---|
| `mindconnect.voice.enabled` | `true` | Registers the endpoint |
| `mindconnect.voice.path` | `/api/voice` | Where it is served |
| `mindconnect.voice.speech-to-text` | `speech-to-text` | LLM config (or alias) that transcribes |
| `mindconnect.voice.text-to-speech` | `text-to-speech` | LLM config (or alias) that speaks |
| `mindconnect.voice.language` | `de` | Spoken language when `start` names none |
| `mindconnect.voice.max-utterance-bytes` | `10485760` | Largest utterance |
| `mindconnect.voice.allowed-origins` | *(none)* | Origin patterns allowed to connect from a browser. Empty means same-origin only; server-side clients send no origin and are always let through. |

## In code

Spoken output is a port of its own, next to chat and transcription:
`LlmSpeech` in `mc-llm-audio-core` speaks a text with a config named by its
name, aliases followed. `mc-llm-audio` has the adapter for the
OpenAI-compatible endpoint `POST /v1/audio/speech`. A library that wants
speech without the WebSocket uses those two modules directly.
