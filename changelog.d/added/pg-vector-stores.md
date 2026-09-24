- **agents:** **Under Postgres persistence the vector stores keep nothing in
  files: their templates and instances are rows, and their vectors pgvector
  tables in the application's own database.** The registry lives in
  `mc_vector_store_template` and `mc_vector_store_instance`, filled once per
  namespace from `vector-stores/templates` and `vector-stores/instances`.
  `pgvector` is the default backend there and needs no
  `mindconnect.vector-store.url`; once the database has the extension, each
  namespace's `memory` stores move over on first use — a store's
  `<store>.jsonl` is imported into its empty pgvector table (a file whose
  embedding dimension does not fit stays on `memory`, logged), and its record
  and every template that named `memory` then name `pgvector`, so new chat
  stores follow. The files are left in place. A database without pgvector — the
  plain `postgres:16-alpine` image — keeps the vectors on `memory` as before,
  with one warning at start naming the fix: pgvector built into the image (an
  Alpine build for an Alpine data directory). A backend set in `mindconnect.vector-store.backend`
  is kept.
