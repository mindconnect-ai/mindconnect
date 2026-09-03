---
id: chat-file-upload
area: chat
requires: [server-9091, lm-studio-embedding-model]
duration: ~5 min
last-verified: never (endpoint path verified by ChatFileUploadSmokeTest, 2026-08-27)
---

# File upload in the chat: attach, search, answer

**Goal:** A file attached via the chat's "+" is ingested into the session's
vector store, announced to the agent, and the agent answers questions about
it via `vector_search`.

## Preconditions

- Admin UI running at http://localhost:9091 (otherwise: SKIPPED)
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

1. New session with an agent that has tools (e.g. `assistant-with-tools`);
   click the **+** next to the input → attach `soup.md`.
   **Expected:** A success toast ("… attached — the agent can now search
   it."); the file appears as a chip above the input.
2. Reload the page (F5).
   **Expected:** The chip is still there (persisted, not stream state).
3. Ask: `Was ist die Geheimzutat der Testsuppe? Nutze deine angehängten
   Dateien.`
   **Expected:** A `vector_search` task card runs; the answer names
   **Paprika**. After the turn the user bubble shows a `📎 soup.md` line
   above the question (rendered from the message's metadata; the text
   itself is what you typed).
4. Check the session's Memory view (session → Memory).
   **Expected:** The user message is shown with a
   `[System note — attached to this chat: soup.md (Markdown) …]` ahead of
   the question — that is what the model received — and the system prompt
   carries an "Attached files" section listing `soup.md (Markdown)`.
5. Ask a second question about the file.
   **Expected:** The answer is right again; the new user bubble has NO 📎
   line — a file is announced once, later turns rely on the system-prompt
   section.
6. Remove the chip (×), then ask the same question again.
   **Expected:** Removal toast. The new user bubble shows a
   `🗑 soup.md removed` line; the agent answers that the file was removed
   and does NOT search the web for it. In the Memory view the new message
   carries a `[System note — removed from this chat: soup.md …]`, the
   earlier attach note no longer names `soup.md`, and the "Attached files"
   section is gone.
7. Attach `soup.md` again and ask once more.
   **Expected:** The new user bubble has a `📎 soup.md` line again (a
   re-attach after a removal is announced afresh) and the answer names
   **Paprika**.

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
