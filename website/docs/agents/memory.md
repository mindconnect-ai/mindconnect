---
title: Memory
sidebar_position: 4
---

# Memory

Each agent keeps its own conversation history. How much of that history reaches
the LLM on every call — and what happens when it no longer fits — is decided by
a per-agent **memory strategy**.

## Memory strategies

A memory strategy decides what of the history is sent with a model call. It
works on the request of that one call and never on the session: the session
keeps every message, so nothing is lost and another strategy can be tried on
the same history. Each agent picks one strategy in its `memory` setting.

| Strategy | What it does | Use when |
|----------|--------------|----------|
| **`window`** | The newest turns that fit; what is older is not sent. Old tool results are shortened first. | **The default** when an agent sets nothing: bounded, and without a model call of its own. |
| **`compact`** | As `window`, but what is no longer sent is summarised first, and the summary travels with every later call. | Long, open-ended chats. |
| **`full`** | Every stored message goes to the LLM, as it was said. | Very large context windows; nothing should ever be left out. |
| **`none`** | Only the turn underway reaches the LLM. | One-shot tools and stateless task runners. |

### How a history is made to fit

For `window` and `compact` the budget is `maxConversationRatio` of the model's
context window (128,000 tokens are assumed for a model whose window is not
known). Three steps, in this order, each only as far as needed:

1. **Old tool results are shown as a note**,
   `[Tool result removed to save context: N tokens, id=<message id>]`. When,
   the agent chooses with `toolResultPruning`:
   - `when_full` (the default) — only once the history no longer fits:
     starting with the oldest, until it fits. The newest
     `keepRecentToolResults` results are never touched. Nothing is taken from
     the model before it must be; a long session carries its results along.
   - `after_turn` — whether or not the history fits: the turn underway and
     the one before it are left alone; of the results before them, the newest
     `keepRecentToolTokens` tokens stay and what is older becomes a note —
     if that frees at least 20,000 tokens. A long session stays small; the
     model reads a result again when it needs it.
   - `off` — never. Results stay as returned until their turn is left out or
     summarised in step 2.

   What the `skill` tool returned is never a note: it is instructions, not a
   result the agent has dealt with. A result shown as a note once stays that
   note in every later call, whatever is chosen later — a history whose
   beginning changes from call to call costs the provider's prompt cache
   every time. The result itself is still in the session: the agent reads
   it again with `fetch_tool_result`, by the id the note names.
2. **The oldest turns are left out.** Turns are left out whole, so a tool call
   never loses its result, and the turn underway is always sent whole. With
   `compact`, the turns left out are summarised first — together with the
   summary there already is — and half the budget stays for what is sent as
   said.
3. **`maxMessages`** — if set, at most so many messages are sent, again
   counted in whole turns.

The summary of a `compact` agent is kept with the session, in its metadata
(`SessionSummaryStore`): it lasts as long as the session and is deleted with
it. It reaches the model as a section *Earlier conversation (summarized)* at
the end of the instructions, or — `summaryPlacement: user_message` — as a
message of the user's ahead of the rest of the history. The model that writes
it is the one named by `mindconnect.agent.stateless.llm-config-name`; without
that setting, the agent's own.

What a session would send right now is visible through the `/memory` endpoint
and in the admin UI's working-memory view: the instructions, the summary, and
every message with whether it is sent and whether it is shortened.

## Configuring the strategy

The setting is the `memory` object in the agent's metadata. Three ways to set
it:

- **Admin UI** — the agent edit form has a *Memory (JSON)* field, pre-filled
  with the setting the agent carries (`{"strategy": "window"}` for an agent
  that carries none). Leave it blank to keep the stored setting; invalid JSON
  and a strategy there is none of are rejected on save. The agent detail page
  shows the active strategy at a glance.
- **Agent JSON file** — `definition.metadata.memory` in the stored shape, or
  the `memoryConfig` object of the flat shape (see
  [below](#memoryconfig-in-agent-files-of-the-flat-shape)), in
  [`agent-definitions/*.json`](./agent-json.md).
- **Nothing** — an agent without a setting runs with `window` in its default
  settings.

## Setting reference

```json
{
  "strategy": "compact",
  "maxConversationRatio": 0.8,
  "maxMessages": 20,
  "toolResultPruning": "when_full",
  "keepRecentToolResults": 3,
  "keepRecentToolTokens": 40000,
  "summaryPlacement": "system_prompt"
}
```

| Field | Default | Meaning |
|-------|---------|---------|
| `strategy` | `window` | `none`, `full`, `window` or `compact`. |
| `maxConversationRatio` | `0.8` | Share of the model's context window the history may take. A share, not tokens: the same agent keeps working when it is given a model with another window. |
| `maxMessages` | no limit | At most so many messages are sent, counted in whole turns. |
| `toolResultPruning` | `when_full` | When an old tool result is shown as a note: `when_full`, `after_turn` or `off`. |
| `keepRecentToolResults` | `3` | `when_full`: the newest tool results are never shortened. |
| `keepRecentToolTokens` | `40000` | `after_turn`: so many tokens of the newest tool results stay as returned. |
| `summaryPlacement` | `system_prompt` | Where the summary of `compact` goes: `system_prompt` or `user_message`. |

`full` and `none` read none of the other fields. A key that is none of
these is refused when the agent form is saved.

### `memoryConfig` in agent files of the flat shape

An agent file in the flat shape names its strategy by `kind`. It is read as
follows:

| `kind` | Becomes |
|--------|---------|
| `full` | `full` |
| `none` | `none` |
| `windowed` | `window` with `maxMessages` = `windowSize` (20 if absent) |
| `summarizing_window` | `compact` if `autoSummarize` is `true`, else `window`; `maxConversationRatio` and `summaryPlacement` are taken over |
| `auto_compact` | `compact`; `compactAtRatio` becomes `maxConversationRatio`, `summaryPlacement` is taken over |

A `toolResultEviction` object, whatever its thresholds, becomes
`toolResultPruning: after_turn`. Every other field of the flat shape —
`compressToolResults`, `maxMessageRatio`, `maxHistoryFetch` and the
thresholds beside them — is not read.

## How a plugin changes what the model is sent

Memory is a plugin like any other (`MemoryPlugin`): it changes the request of
a model call in the `RuntimeHook.MODEL_CALL` hook. That hook is the
extension point for whatever else has to shape the request — a plugin
registers `registry.before(RuntimeHook.MODEL_CALL, …)` and returns the call
with another request (see
[Writing a plugin](./embedding.md#writing-a-plugin)). The working-memory view
in the Admin UI counts tokens with the same counter memory uses.

## What the Compact button does

The session view's *Compact* action (and `POST /api/sessions/{id}/compress`)
asks memory to summarise now:

| Strategy | Compact button |
|----------|----------------|
| `compact` | Summarises everything before the last turn, together with the summary there already is. The last turn stays as said. |
| `window`, `full`, `none` | **Nothing** — these strategies keep no summary. |

## Related: how much of a tool result the model reads

Independent of the memory strategy, the model is shown a tool result one page
at a time. A result longer than the page ends with

`… [showing characters 0-30000 of 184211. Read on with fetch_tool_result: id=<message id>, offset=30000]`

and the model reads on with `fetch_tool_result` (`id`, `offset`, optional
`limit`; one page per call, the last line names the next offset). Every agent
that has tools is offered that tool. Nothing is lost: the session keeps the
result whole, and the chat shows all of it.

The page is **`maxResultChars`**, 30,000 characters unless set:

- for the agent — `definition.metadata.maxResultChars`, agent form: *Max tool
  result chars*;
- for one tool of the agent — `maxResultChars` on the tool reference, tool
  form: *Max result chars*; it takes the place of the agent's.

Separately, a runtime-wide cap of 100,000 characters applies to what a session
keeps of one result. A result past it is cut before it is kept, with a visible
truncation note — that cut is real loss, and reading on ends there.
