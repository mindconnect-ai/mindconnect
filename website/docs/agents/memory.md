---
title: Memory
sidebar_position: 4
---

# Memory

Each agent keeps its own conversation history. How much of that history reaches
the LLM on every turn — and how it is compressed when it grows — is decided by a
per-agent **memory strategy**.

## Memory strategies

A memory strategy controls what goes into the live context window, when it is
compressed, and what (if anything) is added to the system prompt. Each agent
picks one strategy via its `MemoryConfig`.

| Strategy | What it does | Use when |
|----------|--------------|----------|
| **Auto-compact** | History flows through verbatim. When the window crosses a configured ratio of the context window, the whole conversation is collapsed into one summary and the window restarts. | Long, open-ended chats — the "Claude Code" pattern. The bundled conversational agents (`assistant-with-tools`, `research-lead`, `planner`) use it. |
| **Summarizing window** | Keeps a sliding window; summarization of older messages into the system prompt is **opt-in** (`autoSummarize`), and large tool results are compressed once the window crosses a ratio. | **The system default** when an agent has no `memoryConfig`. |
| **Windowed** | Keeps the last *N* stored messages. No summarization. | Predictable, simple recency — older turns just drop off. |
| **Full history** | Every stored message goes to the LLM. | Very large context windows; nothing should ever be dropped. |
| **No memory** | Stateless — only the system prompt and the current message reach the LLM. | One-shot tools and stateless task runners. |

### Auto-compression (auto-compact)

Live messages are sent through unchanged, so the model always sees the real,
recent exchange. After each turn the runtime measures the live window's tokens;
once they exceed `compactAtRatio` of the model's context window, the entire
live conversation is distilled into a single `ConversationSummary`, and the
window restarts from that summary (placed as a `USER_MESSAGE` by default).

The **whole conversation** is the unit of compaction — but there is one
optional, finer knob: `toolResultEviction` (`afterTurns`, `aboveTokens`)
replaces bulky older tool results with a short stub before compaction kicks in.
It is off by default; the agent can pull an evicted result back with the
built-in `fetch_tool_result` tool.

The current strategy and window are visible through the `/memory` endpoint and
in the admin UI's working-memory view.

## Configuring the strategy

The strategy is the agent's `memoryConfig` — a JSON object whose `kind` picks
the strategy and whose remaining fields are that strategy's knobs. Three ways
to set it:

- **Admin UI** — the agent edit form has a *Memory (JSON)* field, pre-filled
  with the agent's **effective** config (so an unconfigured agent shows the
  real defaults it runs with). Leave it blank to keep the stored setting;
  invalid JSON is rejected on save. The agent detail page shows the active
  kind at a glance.
- **Agent JSON file** — the `memoryConfig` object in
  [`agent-definitions/*.json`](./agent-json.md).
- **Nothing** — an agent without a `memoryConfig` runs with
  `summarizing_window` in its default settings (see below).

## Strategy reference

### `summarizing_window` (the system default)

A sliding live window with three independent mechanisms: budget-driven
trimming, opt-in summarization of old ranges, and per-tool-result compression.

```json
{
  "kind": "summarizing_window",
  "maxConversationRatio": 0.80,
  "maxMessageRatio": 0.20,
  "compressToolResults": true,
  "minToolResultCompressTokens": 500,
  "toolResultThresholdRatio": 0.02,
  "compressWhenWindowAboveRatio": 0.50,
  "autoSummarize": false,
  "autoSummarizeRatio": 0.75,
  "summaryPlacement": "SYSTEM_PROMPT",
  "maxHistoryFetch": 500
}
```

| Flag | Default | Meaning |
|------|---------|---------|
| `maxConversationRatio` | `0.80` | Fraction of the context window the live message tail may consume; older messages are trimmed off the window (not deleted) beyond it. |
| `maxMessageRatio` | `0.20` | Cap for a single message — anything larger is truncated in the window. |
| `compressToolResults` | `true` | **Master switch** for tool-result compression. `false` turns all of it off. |
| `minToolResultCompressTokens` | `500` | Floor for the per-result size threshold: smaller results are never compressed. |
| `toolResultThresholdRatio` | `0.02` | The threshold as a fraction of the context window; effective threshold = `max(min, window × ratio)`. |
| `compressWhenWindowAboveRatio` | `0.50` | Compression only starts once the live window exceeds this fraction of the context window — no pressure, no compression. |
| `autoSummarize` | `false` | Opt-in: after a turn, summarize old unsummarized ranges once the live window exceeds `autoSummarizeRatio`. |
| `autoSummarizeRatio` | `0.75` | The trigger for `autoSummarize`. |
| `summaryPlacement` | `SYSTEM_PROMPT` | Where summaries are injected: `SYSTEM_PROMPT` (appended under "Earlier conversation") or `USER_MESSAGE` (synthetic "session is being continued" message, Claude-Code-style). |
| `maxHistoryFetch` | `500` | Page size when loading history. |

#### How tool-result compression decides (the Claude model)

Compression runs at the **start of every turn execution** (first run and every
wake), over the freshly loaded conversation. A result is only compressed when
**all** of these hold:

1. **The model has read it** — an assistant message follows it. The results a
   wake is about are unread and always stay full: a round never loses what it
   just fetched.
2. **It is not among the newest three results** — the model may still be
   comparing recent outputs in detail, whatever turn they belong to.
3. **The window is under pressure** (`compressWhenWindowAboveRatio`).
4. **The single result is above the size threshold.**

Compression is **lossless at the store**: the original stays in the message's
`content`, the stub goes to `compressedContent`, and only the rendered window
shrinks. The stub carries a preview plus the hint *"call the tool again if you
need the full output"*, so the model knows the way back.

### `auto_compact`

The Claude Code pattern — see [Auto-compression](#auto-compression-auto-compact)
above for the behaviour.

```json
{
  "kind": "auto_compact",
  "compactAtRatio": 0.80,
  "summaryPlacement": "USER_MESSAGE",
  "toolResultEviction": { "afterTurns": 1, "aboveTokens": 500 }
}
```

| Flag | Default | Meaning |
|------|---------|---------|
| `compactAtRatio` | `0.80` | Compaction fires when the live window exceeds this fraction of the context window. |
| `summaryPlacement` | `USER_MESSAGE` | Where the one summary lands on the next request. |
| `toolResultEviction` | `null` (off) | Optional: replace tool results **older than `afterTurns` user turns** and **at least `aboveTokens` tokens** with a pointer stub — at render time only, nothing is written. The agent reloads the full content via the built-in `fetch_tool_result` tool (enable it on the agent when using eviction). |

Eviction is the "drop tool traffic after the turn" knob: `afterTurns: 1`
means everything before the current user turn is a candidate. Note that it
has **no** keep-newest-three protection — only the turn boundary and the
token floor guard it.

### `windowed`

```json
{ "kind": "windowed", "windowSize": 20 }
```

| Flag | Default | Meaning |
|------|---------|---------|
| `windowSize` | `20` | The last *N* stored messages go to the LLM; older ones simply fall off. No summaries, no compression. |

### `full`

```json
{ "kind": "full", "maxHistoryFetch": 500 }
```

| Flag | Default | Meaning |
|------|---------|---------|
| `maxHistoryFetch` | `500` | Upper bound on messages loaded — everything loaded goes to the LLM verbatim. No summaries, no compression. |

### `none`

```json
{ "kind": "none" }
```

No flags. Only the system prompt and the current turn's messages reach the
LLM — for one-shot helpers and stateless task runners.

## What the Compact button does

The session view's *Compact* action calls the strategy's `compress()`:

| Strategy | Compact button |
|----------|----------------|
| `summarizing_window` (or nothing configured) | Summarizes unsummarized ranges and compresses large tool results, then refreshes the working-memory snapshot. |
| `auto_compact` | Runs a full compaction (one summary, window restarts). |
| `windowed`, `full`, `none` | **No-op** — these strategies never compress. |

## Related: per-tool result caps

Independent of the memory strategy, every tool reference on an agent can set
**`maxResultChars`** (tool form: *Max result chars*): the tool's output is cut
at persist time with a visible truncation note. Unlike compression this is
**real loss** — the cut part never reaches the store — so it is opt-in per
tool, meant for tools known to over-produce. A runtime-wide safety cap of
100k characters applies to every tool regardless.
