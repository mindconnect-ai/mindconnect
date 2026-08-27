package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agentrest.service.NotConfiguredException;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * External REST API for vector stores — a thin shell over
 * {@link VectorStoreService}, which the admin UI uses too: template and store
 * CRUD, text search, chunk upsert, and document ingestion.
 */
@Tag(name = "Vector Stores", description = "Templates (backend + embedding policy), store "
        + "instances, document ingestion, chunk upsert and semantic search. 503 when "
        + "vector stores are not configured in this application.")
@RestController
@RequestMapping("/api/vector-stores")
public class VectorStoreApiController {

    public record SearchRequest(String query, Integer topK) {}

    public record CreateStoreRequest(String name, String template) {}

    public record IngestRequest(String fileId) {}

    public record IngestResult(String fileId, String summary) {}

    public record UpsertChunkRequest(String id, String fileId, Integer ordinal,
                                     String text, Map<String, String> metadata) {}

    private final VectorStoreService service;

    public VectorStoreApiController(VectorStoreService service) {
        this.service = service;
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

    @Operation(summary = "List store instances")
    @GetMapping("/stores")
    public List<VectorStoreInstance> listStores() {
        return service.instances();
    }

    @Operation(summary = "Get a store instance")
    @GetMapping("/stores/{name}")
    public ResponseEntity<VectorStoreInstance> getStore(@PathVariable String name) {
        return service.instance(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Registers (and creates on first use) a GLOBAL store from a template. */
    @Operation(summary = "Create a store",
            description = "Registers (and creates on first use) a GLOBAL store from a "
                    + "template; the template's settings are copied onto the store.")
    @PostMapping("/stores")
    public ResponseEntity<VectorStoreInstance> createStore(@RequestBody CreateStoreRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return service.createStore(request.name(), request.template())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.internalServerError().build());
    }

    /** Removes the store registration. (Data files stay on the backend.) */
    @Operation(summary = "Delete a store registration",
            description = "Removes the registration; data files stay on the backend.")
    @DeleteMapping("/stores/{name}")
    public ResponseEntity<Void> deleteStore(@PathVariable String name) {
        service.deleteStore(name);
        return ResponseEntity.noContent().build();
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
                    + "the admin UI's store-page upload.")
    @PostMapping("/stores/{name}/ingest")
    public ResponseEntity<IngestResult> ingest(@PathVariable String name,
                                               @RequestBody IngestRequest request) throws IOException {
        if (request.fileId() == null || request.fileId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String summary = service.ingestStoredFile(name, request.fileId());
            return ResponseEntity.ok(new IngestResult(request.fileId(), summary));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Embeds {@code text} and upserts it as a single chunk. */
    @Operation(summary = "Upsert a single chunk",
            description = "Embeds the text with the store's embedding config and stores it "
                    + "as one chunk; id defaults to \"{fileId}:{uuid}\".")
    @PostMapping("/stores/{name}/chunks")
    public ResponseEntity<VectorStoreService.UpsertedChunk> upsertChunk(
            @PathVariable String name, @RequestBody UpsertChunkRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.upsertChunk(name, request.id(), request.fileId(),
                request.ordinal(), request.text(), request.metadata()));
    }

    // ── Search ─────────────────────────────────────────────────────────────

    /** Embeds {@code query} with the store's embedding config and returns the top hits. */
    @Operation(summary = "Semantic search",
            description = "Embeds the query with the store's embedding config and returns "
                    + "the topK most similar chunks with scores (cosine similarity).")
    @PostMapping("/stores/{name}/search")
    public ResponseEntity<List<VectorStoreService.Hit>> search(@PathVariable String name,
                                                               @RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int topK = request.topK() == null || request.topK() <= 0 ? 5 : request.topK();
        return ResponseEntity.ok(service.search(name, request.query(), topK, 0));
    }
}
