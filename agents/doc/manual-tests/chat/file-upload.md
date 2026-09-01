---
id: chat-file-upload
area: chat
requires: [server-9090, lm-studio-embedding-model]
duration: ~5 min
last-verified: never (endpoint path verified by ChatFileUploadSmokeTest, 2026-08-27)
---

# File upload in the chat: attach, search, answer

**Goal:** A file attached via the chat's "+" is ingested into the session's
vector store, announced to the agent, and the agent answers questions about
it via `vector_search`.

## Preconditions

- Admin UI running at http://localhost:9090 (otherwise: SKIPPED)
- LM Studio running with BOTH a tool-capable LLM and an EMBEDDINGS model
  loaded (`GET http://localhost:1234/api/v0/models` shows a loaded
  `"type": "embeddings"` entry — otherwise: SKIPPED)
- An llm-config named `embeddings` exists (seeded since 2026-08-27; older
  data dirs get it on the next server start)

## Setup

1. Prepare a small text/markdown file with a verifiable fact nobody would
   guess, e.g. `soup.md` containing
   `Die Geheimzutat der Testsuppe ist Paprika.` (repeat the line a few times
   so chunking has something to do).

## Steps

1. New session with an agent that has tools (e.g. `default-chat`);
   click the **+** next to the input → attach `soup.md`.
   **Expected:** A success toast ("… attached — the agent can now search
   it."); the file appears as a chip above the input.
2. Reload the page (F5).
   **Expected:** The chip is still there (persisted, not stream state).
3. Ask: `Was ist die Geheimzutat der Testsuppe? Nutze deine angehängten
   Dateien.`
   **Expected:** A `vector_search` task card runs; the answer names
   **Paprika**.
4. Check the session's system-prompt context (session → Memory view).
   **Expected:** The attached file is announced (attached-files note), and
   `vector_search` is active for the session.
5. Remove the chip (×), then ask the same question again.
   **Expected:** Removal toast; the agent can no longer find the fact.

## Cleanup

- Delete the test session (its SESSION-scoped store goes stale with it).

## Notes

- Automated twin for the server path: `ChatFileUploadSmokeTest`
  (mc-agent-admin-ui-app) — boots the real app, uploads via
  `POST /admin/api/sessions/{id}/chat-files`, asserts chunks + announcement;
  skips without an embeddings model. Runtime-level attach+ask twin:
  `RuntimeFileQaExampleTest`.
- Found & fixed 2026-08-27: the seed data had NO `embeddings` llm-config —
  app uploads produced 0 chunks while the ingestion workflow still reported
  success. BOTH fixed: the config is seeded now, and a tool error inside a
  workflow tool-call step fails the workflow (opt-out per step via
  `failOnError=false`) — so a broken ingestion shows an "Attach failed"
  toast instead of a lying success.
