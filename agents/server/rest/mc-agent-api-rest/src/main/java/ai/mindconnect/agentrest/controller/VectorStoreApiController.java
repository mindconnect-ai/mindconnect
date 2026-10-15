package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUser;
import ai.mindconnect.agentrest.auth.VectorStoreAccess;
import ai.mindconnect.agentrest.service.NotConfiguredException;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.vectorstore.embedding.MetadataFilter;
import ai.mindconnect.vectorstore.tools.EntitySelector;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.VectorStore;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * External REST API for vector stores — a thin shell over
 * {@link VectorStoreService}, which the admin UI uses too: template and store
 * CRUD, text search, chunk upsert, and document ingestion.
 *
 * <p>Templates and knowledge bases are shared configuration, open to every
 * authenticated caller. A chat's upload store is its user's alone
 * ({@link VectorStoreAccess}): for anyone else it is not listed, and reading,
 * filling, searching or deleting it answers 404 — like a store that does not
 * exist. Ingestion takes only a file the caller may read.
 */
@Tag(name = "Vector Stores", description = "Templates (embedding policy), stores — named lists "
        + "of files and documents whose chunks live once in the namespace's embedding index — "
        + "ingestion, documents, entries and semantic search with entity and metadata filters. "
        + "A chat's upload store (session-…) answers only to the chat's user. 503 when vector "
        + "stores are not configured in this application.")
@RestController
@RequestMapping("/api/vector-stores")
public class VectorStoreApiController {

    /**
     * @param entities  only these entries of the store — {@code {"id": …}} plus {@code type},
     *                  {@code source}, {@code container} where the id alone is ambiguous; omit for all
     * @param filters   metadata conditions: {@code EQ} on any key; {@code IN}, {@code GTE}, {@code LTE}
     *                  on keys declared as metadata fields
     */
    public record SearchRequest(String query, Integer topK, List<EntitySelector> entities, List<MetadataFilter> filters) {}

    public record CreateStoreRequest(String name, String template) {}

    public record IngestRequest(String fileId) {}

    public record IngestResult(String fileId, String summary) {}

    public record DocumentRequest(List<VectorStore.TextChunk> chunks) {}

    private final VectorStoreService service;
    private final VectorStoreAccess access;

    public VectorStoreApiController(VectorStoreService service, VectorStoreAccess access) {
        this.service = service;
        this.access = access;
    }

    /** The capability isn't configured in this host application. */
    @ExceptionHandler(NotConfiguredException.class)
    public ResponseEntity<String> notConfigured(NotConfiguredException e) {
        return ResponseEntity.status(503).body(e.getMessage());
    }

    // ── Templates ──────────────────────────────────────────────────────────

    @Operation(summary = "List templates")
    @GetMapping("/templates")
    public List<VectorStoreTemplate> templates() {
        return service.templates();
    }

    @Operation(summary = "Create or replace a template",
            description = "The template is the policy (backend, embedding config, ingestion "
                    + "workflow) copied onto stores at creation. The built-in default "
                    + "template cannot be overwritten.")
    @PostMapping("/templates")
    public ResponseEntity<VectorStoreTemplate> saveTemplate(@RequestBody VectorStoreTemplate template) {
        if (template.name() == null || template.name().isBlank()
                || VectorStores.DEFAULT_TEMPLATE.equals(template.name())) {
            return ResponseEntity.badRequest().build();
        }
        service.saveTemplate(template);
        return ResponseEntity.ok(template);
    }

    @Operation(summary = "Delete a template",
            description = "Existing stores keep their copied settings.")
    @DeleteMapping("/templates/{name}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String name) {
        if (VectorStores.DEFAULT_TEMPLATE.equals(name)) return ResponseEntity.badRequest().build();
        service.deleteTemplate(name);
        return ResponseEntity.noContent().build();
    }

    // ── Stores ─────────────────────────────────────────────────────────────

    @Operation(summary = "List store instances",
            description = "The knowledge bases, and of the chat upload stores only the caller's own.")
    @GetMapping("/stores")
    public List<VectorStoreInstance> listStores(@CurrentUser UserId caller) {
        return service.instances().stream().filter(store -> access.reachable(store, caller)).toList();
    }

    @Operation(summary = "Get a store instance")
    @GetMapping("/stores/{name}")
    public ResponseEntity<VectorStoreInstance> getStore(@PathVariable String name, @CurrentUser UserId caller) {
        return service.instance(name)
                .filter(store -> access.reachable(store, caller))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Registers (and creates on first use) a GLOBAL store from a template. */
    @Operation(summary = "Create a store",
            description = "Registers (and creates on first use) a GLOBAL store from a "
                    + "template; the template's settings are copied onto the store. Names starting "
                    + "with session- belong to chat upload stores and are refused (400).")
    @PostMapping("/stores")
    public ResponseEntity<VectorStoreInstance> createStore(@RequestBody CreateStoreRequest request) {
        if (request.name() == null || request.name().isBlank()
                || VectorStoreAccess.isChatStoreName(request.name())) {
            return ResponseEntity.badRequest().build();
        }
        return service.createStore(request.name(), request.template())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.internalServerError().build());
    }

    /** Removes the store registration. (Data files stay on the backend.) */
    @Operation(summary = "Delete a store registration",
            description = "Removes the store and its list; the chunks of stored files stay in the "
                    + "embedding index for the other stores that list them.")
    @DeleteMapping("/stores/{name}")
    public ResponseEntity<Void> deleteStore(@PathVariable String name, @CurrentUser UserId caller) {
        requireReachable(name, caller);
        service.deleteStore(name);
        return ResponseEntity.noContent().build();
    }

    // ── Indexes ────────────────────────────────────────────────────────────

    @Operation(summary = "List the indexes",
            description = "Where the stores' chunks are kept: the built-in 'default' and 'chat-uploads' indexes "
                    + "and the namespace's own — pgvector tables, in the application's database or one of their "
                    + "own, or files. Passwords are never returned.")
    @GetMapping("/indexes")
    public List<VectorStoreService.IndexInfo> indexes() {
        return service.indexes();
    }

    @Operation(summary = "Save an index",
            description = "Defines an index of this namespace — under a built-in name it moves that index. "
                    + "The password is stored encrypted; leave it out to keep the stored one. 400 for a "
                    + "definition that does not check out.")
    @PostMapping("/indexes")
    public ResponseEntity<VectorStoreService.IndexInfo> saveIndex(@RequestBody IndexDefinition index) {
        try {
            return ResponseEntity.ok(service.saveIndex(index));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "Delete an index",
            description = "Deletes the namespace's definition; a built-in index then follows the server's "
                    + "settings again. 400 for a built-in index the namespace never redefined.")
    @DeleteMapping("/indexes/{name}")
    public ResponseEntity<Void> deleteIndex(@PathVariable String name) {
        return service.deleteIndex(name) ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }

    // ── Ingestion & chunks ─────────────────────────────────────────────────

    /**
     * Ingests a stored file (see {@code /api/files}) into the store — the
     * same path the admin UI's store-page upload takes: the template's
     * ingestion workflow when one is configured, built-in extraction and
     * chunking otherwise.
     */
    @Operation(summary = "Ingest a stored file",
            description = "Pushes a file from the file store (see POST /api/files) through "
                    + "the store's ingestion path — the template's ingestion workflow when "
                    + "configured, built-in extraction + chunking otherwise. Same path as "
                    + "the admin UI's store-page upload. The file must be one the caller may "
                    + "read; any other id answers 404.")
    @PostMapping("/stores/{name}/ingest")
    public ResponseEntity<IngestResult> ingest(@PathVariable String name,
                                               @RequestBody IngestRequest request,
                                               @CurrentUser UserId caller) throws IOException {
        if (request.fileId() == null || request.fileId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        requireReachable(name, caller);
        try {
            String summary = service.ingestStoredFile(name, request.fileId(), caller);
            return ResponseEntity.ok(new IngestResult(request.fileId(), summary));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Writes a document of the store, replacing what it had. */
    @Operation(summary = "Write a document",
            description = "Embeds the chunks with the store's embedding config and stores them as the "
                    + "document {id} of this store — text with no file behind it — replacing what the "
                    + "document had. The same chunks again are not embedded again.")
    @PutMapping("/stores/{name}/documents/{id}")
    public ResponseEntity<VectorStoreService.UpsertedDocument> putDocument(
            @PathVariable String name, @PathVariable String id, @RequestBody DocumentRequest request,
            @CurrentUser UserId caller) {
        if (request.chunks() == null || request.chunks().isEmpty()
                || request.chunks().stream().anyMatch(c -> c.text() == null || c.text().isBlank())) {
            return ResponseEntity.badRequest().build();
        }
        requireReachable(name, caller);
        return ResponseEntity.ok(service.upsertDocument(name, id, request.chunks()));
    }

    /** What the store lists. */
    @Operation(summary = "List the entries",
            description = "The files and documents the store lists, with their chunk counts.")
    @GetMapping("/stores/{name}/entries")
    public ResponseEntity<List<VectorStoreService.Entry>> entries(@PathVariable String name, @CurrentUser UserId caller) {
        requireReachable(name, caller);
        return ResponseEntity.ok(service.entries(name));
    }

    /** Takes an entry off the store. */
    @Operation(summary = "Remove an entry",
            description = "Takes the file or document off the store. A document leaves the index with "
                    + "it; a stored file stays for the other stores and chats that list it.")
    @DeleteMapping("/stores/{name}/entries/{entityId}")
    public ResponseEntity<Void> removeEntry(@PathVariable String name, @PathVariable String entityId,
                                            @CurrentUser UserId caller) {
        requireReachable(name, caller);
        return service.removeEntry(name, entityId) == 0
                ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    // ── Search ─────────────────────────────────────────────────────────────

    /** Embeds {@code query} with the store's embedding config and returns the top hits. */
    @Operation(summary = "Semantic search",
            description = "Embeds the query with the store's embedding config and returns "
                    + "the topK most similar chunks of the store's entries with scores (cosine "
                    + "similarity) — among the given entities only (id, plus type/source/container where needed), and passing the metadata filters. "
                    + "A range or list filter on an undeclared key answers 400.")
    @PostMapping("/stores/{name}/search")
    public ResponseEntity<List<VectorStoreService.Hit>> search(@PathVariable String name,
                                                               @RequestBody SearchRequest request,
                                                               @CurrentUser UserId caller) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        requireReachable(name, caller);
        int topK = request.topK() == null || request.topK() <= 0 ? 5 : request.topK();
        try {
            return ResponseEntity.ok(service.search(name, request.query(), topK, 0,
                    request.entities() == null || request.entities().isEmpty() ? null : request.entities(),
                    request.filters() == null ? List.of() : request.filters()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** A 404 for the request when the store is a chat's upload store that is not the caller's. */
    private void requireReachable(String name, UserId caller) {
        if (!access.reachable(name, service.instance(name).orElse(null), caller)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such store");
        }
    }
}
