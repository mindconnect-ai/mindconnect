---
title: Vector store & file store
sidebar_position: 7
---

# Vector store & file store

The knowledge layer of the agents area lives in `agents/vectorstore/` — four
small modules that give agents semantic search over documents and an
id-addressed file storage for uploads:

| Module | What it is |
|--------|------------|
| `mc-vector-store` | The SPI (`VectorStore`, `VectorStoreBackend`, `VectorChunk`) plus the built-in `memory` backend. |
| `mc-vector-store-pgvector` | A Postgres/pgvector backend — plain JDBC, no pool dependency. |
| `mc-vector-store-tools` | The `knowledge` tool group (`vector_upsert`, `vector_search`, `vector_delete_file`, `vector_ingest_file`) plus templates & registry. |
| `mc-file-store` | Id-addressed file storage (OpenAI-Files-API style): save bytes once, read by id — with a `filesystem` backend built in. |

## Vector stores

A **`VectorStore`** is one named corpus: embedded chunks in, cosine-similarity
search out (`upsert`, `search`, `deleteFile`, `listFiles`). A store id maps to
whatever the host defines — a chat session, an agent knowledge base. Backends
are discovered via `ServiceLoader` (`VectorStoreBackend.discover()`), so adding
one is a classpath drop.

### The built-in `memory` backend

Chunks live as JSONL files on disk (embeddings are computed once and never
lost); a store's vectors are loaded onto the heap **lazily on first search**,
so memory tracks the *active* stores, not the total corpus. Idle stores are
unloaded again (`idleSeconds`, default 600) and reloaded on the next access.
A per-store cap (`maxChunksPerStore`, default 100 000 ≈ 0.5–1 GB loaded)
rejects oversized upserts with a clear message — larger corpora belong on
pgvector.

### The `pgvector` backend

Each store is one table (`vs_<storeId>`) with an HNSW cosine index, so
different stores can carry different embedding dimensions. Plain JDBC over
`DriverManager` — one short-lived connection per operation; front the URL with
pgbouncer if you need pooling. The `vector` extension must be installed
(the backend runs `CREATE EXTENSION IF NOT EXISTS vector` on first use).
Config keys: `url` (required), `user` / `password`.

### Templates & instances

A **`VectorStoreTemplate`** is the policy for a family of stores: which
backend they live on, which embedding model fills them (fixed per template, so
every store stays dimension-consistent), and optionally which workflow ingests
documents. A concrete store is just template + name and can be created on the
fly. The host's `mindconnect.vector-store.*` properties form the built-in
`default` template, so everything works before anyone defines templates —
manage them in the Admin UI's **Vector Stores** section (templates, stores,
file upload, semantic search).

## Embeddings

Embedding happens **inside the tools** through the gateway's `LlmEmbeddings`
port. It needs an [LLM config](./llm-config-json.md) of `type: EMBEDDING` —
by default one named **`embeddings`** (`mindconnect.vector-store.embedding-config`).

:::info Two embedding configs are bundled
`embeddings` points at a local LM Studio server (`text-embedding-nomic-embed-text-v1.5`
at `http://localhost:1234`), `openai-embeddings` at OpenAI's
`text-embedding-3-small` with `OPENAI_API_KEY`. Stores are created with the
`embeddings` config; to use OpenAI instead, pick `openai-embeddings` in the
store template (Admin UI → Vector Stores → the `chat-uploads` template) or set
`mindconnect.vector-store.embedding-config`.

There is no dimension to configure: a store takes the dimension of the first
vectors written to it and rejects others afterwards. Switching a template's
embedding config therefore only affects stores created from then on — a store
filled with 768-dimensional nomic vectors will not accept 1536-dimensional
OpenAI ones, so delete and re-ingest it when you switch.
:::

## The knowledge tools

All four tools take a `store` name and speak **text only**:

| Tool | Description |
|------|-------------|
| `vector_upsert` | Replaces one file's chunks in a store (delete + insert — re-ingestion never leaves stale chunks). |
| `vector_search` | Embeds the query, returns the top chunks with score and provenance. |
| `vector_delete_file` | Removes one file from a store. |
| `vector_ingest_file` | Path in, searchable content out: reads the file (docx/PDF/markdown via the document reader when `mc-agent-tools-document` is present, else plain text), chunks OpenAI-style (800/400) and embeds — the one-call ingestion for workflows (`glob → ForEach → vector_ingest_file`). |

### A chat's upload store belongs to its user

Files attached to a chat land in that chat's store, `session-<sessionId>`,
registered with the `SESSION` scope and the chat's user as its **owner**.
The tools reach such a store only when they run for that user: in the chat
itself, in another chat of the same user, in a sub-agent the chat started
(whose `vector_search` without a `store` searches the chat's uploads), or in a
workflow run on the user's behalf — the upload pipeline runs its ingestion
workflow that way, and a workflow used as an agent tool runs its tool steps for
the calling user and session. Everything else is refused, including a workflow
started from the workflow admin or `/api/workflows/{id}/run`: a run outside any
chat reaches no chat's store, whatever user it runs as. Knowledge bases
(`GLOBAL` and `AGENT` stores) stay open.

A `session-…` name nobody registered yet, and an upload store from before
owners were recorded, is reachable from its own chat only; the latter records
its owner the next time its chat writes to it, by an upload or a tool call. A
tool writing into its own chat's store before any upload registers it as that
chat's, owned by its user.

## The file store

**`FileStore`** is deliberately small: `save(name, contentType, stream)`
returns a `StoredFile` with a generated id; `find`, `content`, `list`,
`delete` work by id. Which chat or vector store uses a file is somebody else's
association. Content access is stream-based, so consumers stay
backend-agnostic — the built-in `filesystem` backend streams from disk
(default `data/files`); an s3-style backend is a `FileStoreBackend`
ServiceLoader drop away.

The Admin UI's chat **file attachments** and the vector-store file upload run
through it, and `AgentRuntimeBuilder.attachFile(...)` uses it for embedding
scenarios (see `mc-agent-simple-demo`).

### How the agent learns about an attachment

A file attached to a chat is ingested into the session's vector store and
`vector_search` is activated for the session — with two exceptions that
reach the model directly instead (see
[images and documents](#images-and-documents-as-message-parts) below): an
**image** is not ingested at all, a **PDF** is ingested *and* sent with the
next message. For everything else the model is told twice, in two different
places:

- **In the user's next message.** That message records the newly attached
  files in its metadata; when the request is built, the model reads a short
  system note ahead of the user's text — the file names, their kind, and
  that `vector_search` is the way to their content because they are not on
  the filesystem. The stored text stays what the user typed; the chat shows
  a 📎 line above it.
- **In the system prompt.** An "Attached files" section lists every file
  currently attached, rendered fresh each round.

Removing a file is announced the same way: the next user message records
it as removed and the model reads a note that the content is no longer
available and must not be searched for. Neither event rewrites an earlier
message — the record is read in order, so a file attached again after a
removal is announced again, and an attach notice names only files that are
still attached. All of this is rendered by the `LlmMessageMapper`
(see [Memory](./memory.md#how-messages-reach-the-model)).

### Images and documents as message parts

A message is made of **content parts** — its text, plus the images and
files sent with it, referenced by the id the file store holds them under.
An image or PDF attached to the chat becomes a part of the user's next
message, so a model that reads it sees the picture with the question:

- The model's `LlmConfig` says what it reads — `capabilities` with `VISION`
  for images, `DOCUMENTS` for PDFs, or the provider's default when the config
  declares nothing (see the
  [LLM config reference](./llm-configs-reference.md)). A part the model
  reads travels inline, as the provider's content block; otherwise a
  placeholder line stands in for it — what the file is, and why it is not
  here. A PDF a model does not read still reaches it through
  `vector_search`, the placeholder says so. One caveat on OpenAI-compatible
  servers: the `file` content block is OpenAI's own (OpenRouter and Azure
  take it too); on LM Studio, Ollama, Groq, Mistral and the like a config
  that declares `DOCUMENTS` gets the document as a text note, images as
  usual.
- The working-memory budget counts media at an estimate — 1,600 tokens per
  image, one token per 100 bytes of a document, at least 1,000 — since
  there is no text to count. The LLM call trace records the request with
  the base64 payloads replaced by a note (media type and length), so the
  trace store does not grow by the size of every attachment.
- Media goes with the message **in the current turn only**. A request
  repeats the whole history, and an image repeated in every request costs
  its tokens every time. From the next turn on the placeholder names the
  file and the agent asks for it again with the `view_attachment` tool,
  which inserts the image or document as a message of its own into the
  running turn. The tool is activated for a session when an image or PDF is
  attached; the chat shows the re-shown attachment as such.
- The chat bubble shows the image; the REST API accepts parts in the JSON
  chat body, the protocol bridge accepts `Image` and `Document` parts, and
  `AgentRuntime.chat(sessionId, parts, …)` takes them in an embedding.

Images are not named in the attachment notice or the system-prompt
section — they are not indexed, and the part (or its placeholder) speaks
for itself.

## Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `mindconnect.vector-store.backend` | `memory` | Backend type (`memory`, `pgvector`, or your own). |
| `mindconnect.vector-store.url` / `.user` / `.password` | — | `pgvector` backend connection. |
| `mindconnect.vector-store.embedding-config` | `embeddings` | Name of the `EMBEDDING` LLM config. |
| `mindconnect.file-store.backend` | `filesystem` | File-store backend type. |

The `memory` vector-store backend and the `filesystem` file store keep their
files next to everything else of the namespace: `<mindconnect.data.base-dir>/<mindconnect.namespace>/vector-stores`
and `…/files` (default `data/local/vector-stores`, `data/local/files`).
