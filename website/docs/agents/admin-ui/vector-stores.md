---
title: Vector Stores
sidebar_position: 4
---

# Vector Stores

The **Vector Stores** section manages the [knowledge layer](../vector-store.md)
— templates, store instances and the file store. It uses the same resolution
the knowledge tools use, so what the UI shows is exactly what the tools do.
The page has four tabs:

## Templates

A template is the preset for a family of stores: embedding config, optional
ingestion workflow, the [index](#indexes) its stores keep their chunks in, and
a description. A new store copies these settings — stores created on the fly
(a chat's first upload, a tool's first `vector_upsert`) take them from the
template they name.

- **New Template** / **Edit** / **Delete** per row. Deleting a template keeps existing stores —
  they carry their copied settings. The `default` template is built in
  (assembled from the host's `mindconnect.vector-store.*` properties) and
  always present.

## Indexes

Where the stores' chunks are kept: the built-in `default` (`mc_embedding`) and
`chat-uploads` (`mc_embedding_chat`) and the namespace's own, each with where it
actually lives — a pgvector table in the application's database or one of its
own, or files, and why when a pgvector index fell back to files — and which
templates use it. **New Index** / **Edit** take a name, the kind, the table, a
JDBC URL, user and password (stored encrypted, or `${ENV_VAR}`), a directory
for files, and a description; saving opens the index at once, so a wrong URL
shows there. Editing a built-in index saves a definition of the namespace under
its name; deleting that goes back to the server's settings.

A store's page names its index and where it lives.

## Stores

The store instances created from the templates — name, template, scope, and
how many entries they list with how many chunks. Stores from before 0.9 are
imported into the index when the namespace is first opened and show up here
like any other.

**View** opens the store detail:

- an **Entries** table — stored files by their name, documents of the store
  by their id, with type and chunk count — and **Remove** per entry: a document
  goes with it, a stored file stays searchable in the other stores and chats
  that list it,
- **Upload & Ingest** — drag & drop `.docx`/`.pdf`/`.md`/`.txt` straight into
  the store; each file runs through the template's ingestion workflow (or
  direct chunking when the template names none),
- **Test Search** — query, max results, min-score filter; hits come back as a
  table with score, provenance, metadata and text.

## Files

The [file store](../vector-store.md#the-file-store) itself: raw uploads
addressed by id, independent of any vector store. Upload, download and delete
files here; attach one to a chat via `POST /api/sessions/{sessionId}/files`,
or ingest it from a store page. Deleting a file leaves its chunks in the
embedding index; the stores that list it keep finding it until it is taken off
them.
