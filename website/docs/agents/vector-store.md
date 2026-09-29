---
title: Vector store & file store
sidebar_position: 7
---

# Vector store & file store

The knowledge layer of the agents area lives in `agents/vectorstore/` — small
modules that give agents semantic search over everything with text and an
id-addressed file storage for uploads:

| Module | What it is |
|--------|------------|
| `mc-vector-store` | The embedding index — `EmbeddingIndex`, `EntityRef`, `EntityType`, `EmbeddingQuery`, `MetadataField` — with its heap implementation, on files or without persistence. |
| `mc-vector-store-pgvector` | `PgEmbeddingIndex`: the index in one pgvector table, on plain JDBC. |
| `mc-vector-store-tools` | Named stores (templates, instances, member lists) and the `knowledge` tool group (`vector_upsert`, `vector_search`, `vector_delete_file`, `vector_ingest_file`). |
| `mc-file-store` | Id-addressed file storage (OpenAI-Files-API style): save bytes once, read by id — with a `filesystem` backend built in. |

## The embedding index

There is one **`EmbeddingIndex`** per namespace for everything with text —
stored files, mail, calendar events, todos, drive items, and documents written
straight into a store. Entries are keyed by an
`EntityRef(type, source, container, id)` and the embedding model, with the
owner and a version beside them, so an entity is embedded once per model and
only again when its version changes:

```java
EntityRef mail = new EntityRef(EntityType.MAIL, "email.freemail", "INBOX", "1712345678-42");
index.replace(mail, alice, etag, model, chunks);             // atomic, serialised per entity
index.relocate(mail, mail.movedTo("Archive", "1712345699-7")); // moved, not re-embedded
EntityRef file = EntityRef.file(storedFile.id());            // the same ref in every store that lists it
```

`EntityType` names the kinds this repository indexes (`file`, `document`,
`mail`, `calendar-event`, `todo`, `drive-item`); a module that brings another
kind names it with `EntityType.of("…")`. `source` is the account or store an
entity comes from, `container` where it lies in it — a mail folder, a calendar,
a todo list — so a search can take "Alice's mail in `INBOX`" without listing
ids.

The index knows no data pools, sessions or permissions. A search either names
its entities — a pool's or a chat session's files, as a list the caller has
already authorised — or narrows down by type, owner, source and container, and
must then say whose entries it means (`owner`, `ownerOrShared`, `sharedOnly`,
or deliberately `anyOwner`):

```java
index.search(EmbeddingQuery.of(model, sessionFiles), vector, 10);
index.search(EmbeddingQuery.ofTypes(model, EntityType.MAIL).owner(alice).in("email.freemail", "INBOX"), vector, 10);
index.search(EmbeddingQuery.ofTypes(model, EntityType.MAIL, EntityType.CALENDAR_EVENT, EntityType.FILE)
        .ownerOrShared(alice), vector, 10);
```

`index.list(query)` returns the same selection without ranking — what an admin
screen shows, with owner, version and chunk count per entity.

### Metadata filters and declared fields

Every chunk carries a small string map of metadata. `where(key, value)` filters
on equality, on any key. Ranges and lists need the key declared for its entity
type, once, at startup:

```java
index.declareField(new MetadataField(EntityType.MAIL, "received_at", MetadataField.Kind.TIMESTAMP));
index.declareField(new MetadataField(EntityType.TODO, "priority", MetadataField.Kind.NUMBER));

index.search(EmbeddingQuery.ofTypes(model, EntityType.MAIL).owner(alice)
        .atLeast("received_at", "2026-09-01T00:00:00Z")
        .whereIn("folder_kind", "inbox", "archive"), vector, 10);
```

A declared field's values are checked on the way in — a `NUMBER` must be a
number, a `TIMESTAMP` an ISO-8601 instant, stored in one fixed UTC form so that
its text sorts like time. `whereIn`, `atLeast`, `atMost` and `between` on a key
that is not declared for every type the search can reach are refused, so a
missing database index shows up as an error rather than a slow scan.

### Filter first, rank second

Every search compares only the selected entries with the query vector,
exactly. Filtering after a global nearest-neighbour search — what an HNSW index
invites — returns too few hits, or none, when the selection is a small part of
the table.

`PgEmbeddingIndex` keeps every namespace's entries in the one table
`mc_embedding` (created on first use; needs PostgreSQL 12+ with `vector`). The
selection runs in a `MATERIALIZED` CTE over the primary key (by ref), the owner
index (by attributes), the GIN index on the metadata (equality) or a declared
field's own partial index; there is deliberately no HNSW index. That is cheap
up to some tens of thousands of chunks per search. The `embedding` column has
no fixed dimension, so models of different sizes share the table. Declared
fields are kept in `mc_embedding_field`, for the whole table.

On file persistence the index is a `MemoryEmbeddingIndex` over a
`FileEntryStore`: one JSON file per entity and model under
`<data dir>/<namespace>/embeddings`, loaded onto the heap on first use — one
copy per directory and JVM.

## Vector stores

A **vector store** is a named list of entities — files, documents, mail — plus
its settings: the embedding model, an optional ingestion workflow, a scope and
an owner. The chunks are not the store's: they live once per entity in the
index, and a file listed by three stores, or attached to three chats, is
embedded once. `VectorStores.open(…)` returns a `VectorStore` handle:

```java
VectorStore store = stores.open(ns, "handbook", "knowledge", Scope.GLOBAL, null);
store.put(EntityRef.file(fileId), owner, "1", chunks);   // embeds unless indexed already, then lists it
store.add(EntityRef.file(otherFileId));                  // lists an entity that is indexed
store.search("cancellation period", 5, onlyTheseFiles, List.of(filter));
store.remove(ref);   // a document of the store leaves the index; a stored file stays for the others
```

A search only ever sees the store's members, and may narrow down further — to
some of them, by metadata.

### Indexes

A store says what belongs together; an **index** says where its chunks are
kept. Each template names one (`index`), and its stores use it:

| Index | Where |
|-------|-------|
| `default` (built in) | `mc_embedding` — in the application's database when it has pgvector, in a database of its own with `mindconnect.vector-store.url`, else files under `<data dir>/<namespace>/embeddings` |
| `chat-uploads` (built in) | the same place, table `mc_embedding_chat` (files: `embeddings-chat-uploads`) — the files attached to chats, apart from the rest |
| your own | a pgvector table in the application's database or in another one (JDBC URL, user, password), or a directory of files |

A namespace defines its own indexes in the admin UI's **Indexes** tab or
through `POST /api/vector-stores/indexes`; saving one under a built-in name
moves that index for the namespace — a tenant's `default` into a database of
its own, say. Passwords are stored encrypted like an LLM config's key; a
`${VAR}` placeholder is read from the environment. A pgvector index whose
database has no pgvector falls back to files, and says so where it is shown.

Indexes do not share their entries: a file in a chat and in a knowledge base on
another index is embedded in each. Within one index it is embedded once.

### Templates & instances

A **`VectorStoreTemplate`** is the policy for a family of stores: which
embedding model fills them and optionally which workflow ingests documents. A
concrete store is template + name and can be created on the fly. The host's
`mindconnect.vector-store.*` properties form the built-in `default` template,
so everything works before anyone defines templates — manage them in the Admin
UI's **Vector Stores** section (templates, stores, file upload, semantic
search). Where the vectors live is the template's index.

### Stores from before 0.9

Before 0.9 every store had storage of its own: a `<store>.jsonl` file on the
`memory` backend, a `vs_<namespace>__<store>` table on pgvector. When a
namespace is first opened, their chunks are read into the index once — as
documents of their store, the old per-store file id as the document's id — and
the store lists them. A store that lists something already is not imported
again; the old files and tables are left in place.

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

There is no dimension to configure: the index keeps each model's vectors
apart — by provider and model of the config, followed through aliases — so
switching a template's embedding config simply means the store's entities are
embedded again under the new model when they are next ingested.
:::

## The knowledge tools

All four tools take a `store` name and speak **text only**:

| Tool | Description |
|------|-------------|
| `vector_upsert` | Embeds one file's or document's chunks and lists it in the store, replacing what it had — unless the text is the same, which is not embedded again. `type: file` names a stored file by its id (shared with every store that lists it), `document` (default) text of this store alone. |
| `vector_search` | Embeds the query, returns the top chunks of the store's entries with score and provenance — optionally among some `entities` only — by id, plus type, source or container where the id alone is ambiguous, as each hit names its entry — and `where` metadata has given values. |
| `vector_delete_file` | Takes one file or document off the store. A stored file stays searchable in the other stores and chats that list it. |
| `vector_ingest_file` | Path in, searchable content out: reads the file (docx/PDF/markdown via the document reader when `mc-agent-tools-document` is present, else plain text), chunks OpenAI-style (800/400) and embeds — the one-call ingestion for workflows (`glob → ForEach → vector_ingest_file`). |

The seeded `file-ingestion` workflow takes an optional `file_id`: the upload
paths pass the stored file's id, so an attached file is indexed once under it.

### A chat's upload store belongs to its user

Files attached to a chat are listed in that chat's store, `session-<sessionId>`,
registered with the `SESSION` scope and the chat's user as its **owner** — as
stored files, so a file attached to a second chat is not embedded again.
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
  how to reach them: a file stored on disk by its path, with the file and
  document tools; a file without a path through `vector_search`, because it
  is not on the filesystem. The stored text stays what the user typed; the
  chat shows a 📎 line above it.
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
| `mindconnect.vector-store.backend` | follows the persistence | Where the embedding index lives: `pgvector`, or `file`. Under Postgres persistence the index is in the application's database when it has pgvector, in files otherwise. |
| `mindconnect.vector-store.url` / `.user` / `.password` | — | A pgvector database of the index's own; unset under Postgres persistence: the application's database. |
| `mindconnect.vector-store.embedding-config` | `embeddings` | Name of the `EMBEDDING` LLM config of the built-in template. |
| `mindconnect.file-store.backend` | `filesystem` | File-store backend type. |

On file persistence everything lives next to the rest of the namespace under
`<mindconnect.data.base-dir>/<mindconnect.namespace>/`: the index in
`embeddings/`, the registry in `vector-stores/templates`, `vector-stores/instances`
and `vector-stores/members`, the files in `files/`. Under Postgres persistence
the registry is kept in `mc_vector_store_template`, `mc_vector_store_instance`
and `mc_vector_store_member` (filled once per namespace from those directories),
the index in `mc_embedding` and `mc_embedding_field`.
