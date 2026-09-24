- **agents:** **mail views and the mail index can live in Postgres.**
  `PgViewStore` keeps a person's mail views as one row per view in
  `mc_mail_view`, where the file store kept one JSON document per person —
  two tools adding to two lists at once no longer fail with "Could not write
  the mail views" or lose one of the lists. Given the file store as its import
  source, it copies a person's views from
  `<dataDir>/<namespace>/mail-views/<user>.json` the first time it sees them
  and leaves the file in place. `PgMailIndexStore` keeps the index as a row
  per mail: `mc_mail_window` holds each folder window (total, when it was
  synced), `mc_mail_head` one row per message head in it, written together in
  one transaction; a head that leaves the window leaves the table. Each head
  row has an `embedding vector` column prepared for a semantic search over
  mail — nothing fills it yet, and saving a head never touches it. Without
  pgvector in the database the column is left out, with one warning at start,
  and added on the first start that finds the extension. The index is a cache
  and is not imported from the files; it fills again. Both stores take the
  namespace per call, so one bean serves every namespace, and create their
  tables with `initSchema()`. The host chooses the store — the file adapters
  are unchanged.
