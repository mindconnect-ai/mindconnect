- **agents:** **one vector index for the chunks of every file, searched by file list.**
  `FileVectorIndex` (in `mc-vector-store`) keeps the embedded chunks of stored
  files by file and embedding model — a file is embedded once, whichever data
  pools or chat sessions list it. A search takes the files to look in (a
  pool's or a session's file list) and always filters first, then ranks only
  those chunks by cosine similarity, so a small pool in a large table still
  gets its full `topK` and nothing outside the list comes back; no files, no
  hits. `PgFileVectorIndex` keeps every namespace's chunks in the one table
  `mc_file_chunk`, created on first use, with an exact ranking over the
  candidates instead of an HNSW index; `MemoryFileVectorIndex` does the same on
  the heap. Nothing uses it yet.
