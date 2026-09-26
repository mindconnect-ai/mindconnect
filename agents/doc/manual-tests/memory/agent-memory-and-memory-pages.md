---
id: memory-agent-memory-and-memory-pages
area: memory
requires: [server-9090, lm-studio-tool-model]
duration: ~8 min
last-verified: 2026-09-26 (feature/user-memory-ui, before first commit; OpenAI gpt-5.4-mini, chats via REST, pages in the browser)
---

# Agent memory, the profile's Memory tab and the admin's Memory page

**Goal:** An agent whose memory tools are bound with `scope: agent` keeps what
it learns in a memory of its own — which it finds again in a new chat and no
other agent sees — while `default-chat` keeps writing to the shared memory;
the user sees and deletes both kinds on their profile, an admin sees every
user's under Data → Memory.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- A tool-capable LLM behind `agent-default` (otherwise: SKIPPED)
- `default-chat` has `memory_write`, `memory_read`, `memory_delete`

## Setup

1. Create an agent `secretary` (system prompt: "You are the user's personal
   secretary: you book travel and remember how they like things arranged.
   Be brief.", LLM config `agent-default`).
2. Give it the tools `memory_write`, `memory_read`, `memory_delete`, each with
   the override `scope` = `agent` (tool binding form, or
   `PUT /api/agents/{id}/tools` with `"overrides": {"scope": "agent"}`).

## Steps

1. New chat on `default-chat`: `Please remember: I lead the purchasing team in Erlangen.`
   **Expected:** `memory_write`; the entry lies directly under
   `<data>/<ns>/memory/<user>/` (Postgres: id `<user>/<name>`, `agent_id` empty).
2. New chat on `secretary`: `Remember for my travel: I always fly Lufthansa and want an aisle seat.`
   **Expected:** `memory_write`; the entry lies under
   `<data>/<ns>/memory/<user>/agents/<secretary id>/` (Postgres: id
   `<user>/@<secretary id>/<name>`).
3. Another new chat on `secretary`: `What do you know about how I travel? And what is my job?`
   **Expected:** it knows Lufthansa and the aisle seat, and does **not** know
   the job — the shared memory is not its own.
4. Profile → **Memory** tab.
   **Expected:** both entries; the memory column says "Shared by all agents"
   and "Agent: secretary". **View** opens the entry in a dialog with its
   lines ("Why:", "How to apply:") on lines of their own.
5. Data → **Memory** (`/admin/memories`).
   **Expected:** the same entries with a User column; typing part of a user id
   into the filter and Enter narrows the table.
6. AI → Agents → `secretary` → **Memory** tab.
   **Expected:** only the secretary's own entry, with the user; `default-chat`'s
   page has no Memory tab (it works on the shared memory).
7. `curl http://localhost:9090/api/memories` (with a token when auth is on).
   **Expected:** both entries, the secretary's with `agentId`;
   `?agent=shared` narrows to the shared one.
8. **Delete** the shared entry on the admin page and confirm.
   **Expected:** "Memory deleted", the table shows one entry, the file is gone.

## Cleanup

- Delete the secretary's entry on the profile's Memory tab; delete the
  `secretary` agent.

## Notes

- The confirm of a Delete is the browser's own `confirm()` — an automated
  browser may dismiss it silently, so nothing seems to happen.
- As a plain member of a namespace (not an admin): the profile's Memory tab
  and `/api/memories` work, `/admin/memories` answers 403.
