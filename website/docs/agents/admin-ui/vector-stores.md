---
title: Vector Stores
sidebar_position: 4
---

# Vector Stores

The **Vector Stores** section manages the [knowledge layer](../vector-store.md)
— templates, store instances and the file store. It uses the same resolution
the knowledge tools use, so what the UI shows is exactly what the tools do.
The page has three tabs:

## Templates

A template is the *policy* for a family of stores: backend (`memory`,
`pgvector`), embedding config, optional ingestion workflow, description.

- **New Template** — the form swaps its backend-specific settings (dir vs.
  url/user/password) when you change the backend dropdown.
- **Edit** / **Delete** per row. Deleting a template keeps existing stores —
  they carry their copied settings. The `default` template is built in
  (assembled from the host's `mindconnect.vector-store.*` properties) and
  always present.

## Stores

The store instances created from the templates — name, template, backend,
scope and live chunk count. Stores that physically exist but were never
registered (created outside the tools) show up as `(unregistered)`.

**View** opens the store detail:

- a **Files** table (file → chunk count) with per-file delete,
- **Upload & Ingest** — drag & drop `.docx`/`.pdf`/`.md`/`.txt` straight into
  the store; each file runs through the template's ingestion workflow (or
  direct chunking when the template names none),
- **Test Search** — query, max results, min-score filter; hits come back as a
  table with score, provenance, metadata and text.

## Files

The [file store](../vector-store.md#the-file-store) itself: raw uploads
addressed by id, independent of any vector store. Upload, download and delete
files here; attach one to a chat via `POST /api/sessions/{sessionId}/files`,
or ingest it from a store page. Deleting a file leaves already-ingested chunks
untouched.
