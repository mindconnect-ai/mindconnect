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

:::warning No embedding config is bundled
None of the bundled LLM configs is an embedding config, so the knowledge tools
report themselves unavailable until you create one (e.g. an OpenAI
`text-embedding-3-small` config named `embeddings`).
:::

## The knowledge tools

All four tools take a `store` name and speak **text only**:

| Tool | Description |
|------|-------------|
| `vector_upsert` | Replaces one file's chunks in a store (delete + insert — re-ingestion never leaves stale chunks). |
| `vector_search` | Embeds the query, returns the top chunks with score and provenance. |
| `vector_delete_file` | Removes one file from a store. |
| `vector_ingest_file` | Path in, searchable content out: reads the file (docx/PDF/markdown via the document reader when `mc-agent-tools-document` is present, else plain text), chunks OpenAI-style (800/400) and embeds — the one-call ingestion for workflows (`glob → ForEach → vector_ingest_file`). |

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

## Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `mindconnect.vector-store.backend` | `memory` | Backend type (`memory`, `pgvector`, or your own). |
| `mindconnect.vector-store.dir` | `data/vector-stores` | `memory` backend: store files. |
| `mindconnect.vector-store.url` / `.user` / `.password` | — | `pgvector` backend connection. |
| `mindconnect.vector-store.embedding-config` | `embeddings` | Name of the `EMBEDDING` LLM config. |
| `mindconnect.file-store.backend` | `filesystem` | File-store backend type. |
| `mindconnect.file-store.dir` | `data/files` | `filesystem` backend root. |
