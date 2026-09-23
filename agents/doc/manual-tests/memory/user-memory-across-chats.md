---
id: memory-user-memory-across-chats
area: memory
requires: [server-9090, lm-studio-tool-model]
duration: ~5 min
last-verified: 2026-09-23 (feature/cross-session-memory, before first commit; OpenAI gpt-5.4-mini, steps via REST)
---

# User memory: what one chat saves, the next chat knows

**Goal:** An agent with the memory tools saves what the user asks it to
remember with `memory_write`, and a new chat with the same user sees the
entry in its system prompt and uses it — while another user sees nothing.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio running with a tool-capable LLM loaded (otherwise: SKIPPED)
- `default-chat` has the tools `memory_write`, `memory_read` and
  `memory_delete` enabled (a fresh installation has them; otherwise add them
  on the agent's edit page)

## Setup

1. Open a new chat on `default-chat`. If a previous run left a memory, send
   `Forget everything you remember about my favourite colour.` and wait for
   the answer.

## Steps

1. In a new chat on `default-chat`, send:
   `Please remember: my favourite colour is teal, and I like short answers.`
   **Expected:** a tool card `memory_write` (one or two calls), then a short
   confirmation.
2. Open the chat's working-memory view (`/api/sessions/{id}/memory`, or the
   memory panel in the admin UI).
   **Expected:** the system prompt contains a `## Memory` section listing the
   new entry/entries, e.g. `- favourite-colour (user): …`.
3. Start a **new** chat on `default-chat` and send: `What is my favourite colour?`
   **Expected:** the answer says teal — with or without a `memory_read` call
   first — and without asking back.
4. With file persistence, check the data directory:
   `ls <data>/<namespace>/memory/<user>/`
   **Expected:** one `.md` file per entry, front matter with `name`,
   `description`, `type`. With Postgres:
   `SELECT id, user_id FROM mc_user_memory;` shows `<user>/<name>` rows.
5. Open a new chat as another user (a second browser profile, or the API
   with another token) and ask the same question.
   **Expected:** the agent does not know; its system prompt says
   `No memories saved yet — there is nothing to read.`

## Cleanup

- As the first user: `Forget my favourite colour and that I like short answers.`
  **Expected:** `memory_delete` tool cards; the `## Memory` section of the next
  round no longer lists the entries.

## Notes

- Whether the model calls `memory_write` unasked (without "remember") depends
  on the model; this case asks explicitly so it does not.
- A small local model may name the entry differently (`favorite-color`,
  `colour-preference`): judge by the description, not the name.
- Cleanup must leave no file behind. A model that answers "forget" with a
  `memory_delete` and a `memory_write` of the same name in one round (a note
  that it forgot) is a FAIL of the prompt rules, not of the model's manners.
- When LM Studio serves several clients at once, gpt-oss streams may end with
  `terminated` (HTTP 500 in the chat) — unrelated to this case; retry, or
  point `agent-default` at another model.
