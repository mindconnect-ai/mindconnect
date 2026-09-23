- **agents:** **one embedding index for everything with text — files, mail, calendar events, todos.**
  `EmbeddingIndex` (in `mc-vector-store`) keeps embedded chunks by entity
  (`type`, `source`, `container`, `id`) and embedding model, with the owner and
  a version beside them, so an entity is embedded once and only again when its
  version changes. A search either names the entities — a data pool's or a chat
  session's files — or narrows down by type, owner, source and container
  ("Alice's mail in `INBOX`"), and always filters first, then ranks only those
  chunks by cosine similarity: a small selection in a large table still gets
  its full `topK`, and nothing outside it comes back. `relocate` moves an
  entity's entries when a mail changes folder, without embedding it again.
  `PgEmbeddingIndex` keeps every namespace's entries in the one table
  `mc_embedding`, created on first use, with exact ranking instead of an HNSW
  index and per-entity locking for concurrent re-indexing;
  `MemoryEmbeddingIndex` does the same on the heap. Nothing uses it yet.
