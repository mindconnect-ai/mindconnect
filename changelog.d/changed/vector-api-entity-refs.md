- **agents:** **one vector-store API: stores list entities, indexes hold their chunks — breaking.**
  `VectorStore`, `VectorChunk` and `VectorStoreBackend` (with the `memory` and
  `pgvector` backends) are gone. A vector store is now a named list of
  `EntityRef`s — stored files, documents of the store, mail, … — whose chunks
  live once per entity in an `EmbeddingIndex`: a file attached to three chats
  or listed by three stores on one index is embedded once. `EntityType` is a
  type of its own; `EmbeddingQuery` narrows a search by refs or by type, owner,
  source and container, and by metadata — `where` on any key, `whereIn`,
  `atLeast`, `atMost`, `between` on keys declared with
  `EmbeddingIndex.declareField` (Postgres keeps a partial index per field).
  Where chunks are kept is the index a template names: the built-in `default`
  (`mc_embedding`) and `chat-uploads` (`mc_embedding_chat` — chat uploads apart
  from the rest), and indexes a namespace defines itself — another table, a
  database of its own (JDBC URL, user, encrypted password), or files — in the
  admin UI's new Indexes tab or `…/api/vector-stores/indexes`. Indexes do not
  share entries.
  `vector_search` takes `entities` (id, plus type, source, container where
  needed) and `where`; `vector_upsert` a `type` (`file` for a stored file's id)
  and a `name`; the seeded `file-ingestion` workflow a `file_id`. REST:
  `…/chunks` is replaced by `PUT …/documents/{id}`, new `GET`/`DELETE
  …/entries`, search takes `entities` and `filters`, hits name their entity.
  Templates and instances lose `backend` and `backendConfig` and gain `index`;
  `mindconnect.vector-store.backend` now picks `pgvector` or `file`. A store's
  page has Entries and Chunks tabs with search and paging; the Files tab says
  where each file is used. On first use of a namespace the stores kept before
  — JSONL files and `vs_*` tables — are imported into their indexes once and
  left in place.
