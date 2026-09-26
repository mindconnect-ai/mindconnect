package ai.mindconnect.agentrest.service;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.IndexedEntity;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.MetadataFilter;
import ai.mindconnect.vectorstore.tools.DirectIngestion;
import ai.mindconnect.vectorstore.tools.IndexDefinition;
import ai.mindconnect.vectorstore.tools.EntitySelector;
import ai.mindconnect.vectorstore.tools.VectorStore;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorTools;
import ai.mindconnect.vectorstore.tools.VectorStores;
import ai.mindconnect.workflow.admin.run.WorkflowRunService;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one place vector-store operations live: template and store CRUD, text
 * search, chunk upsert, and document ingestion. Both surfaces delegate here —
 * the admin UI's vector-store pages and the external REST API — so behaviour
 * (spool location, ingestion-workflow-vs-direct rule, chunking) can never
 * drift between them.
 *
 * <p>Vector stores and the workflow engine are optional in a host
 * application; methods throw {@link NotConfiguredException} when the
 * capability is absent instead of failing the context at startup.
 */
@Service
public class VectorStoreService {

    /**
     * One search result — the entity it belongs to, the chunk's content and
     * score, without the raw embedding.
     *
     * @param entityType {@code file} for a stored file, {@code document} for text of this store alone, …
     * @param entityId   the file's id, the document's id, …
     */
    public record Hit(String entityType, String source, String container, String entityId, String chunkId,
                      int ordinal, String text, Map<String, String> metadata, double score) {}

    /** One entity a store lists, as the index has it. */
    public record Entry(String entityType, String source, String container, String entityId, String owner,
                        String version, long chunks) {}

    /** The outcome of writing a document: its id and how many chunks it has now. */
    public record UpsertedDocument(String id, long chunks) {}

    private final ObjectProvider<VectorStores> storesProvider;
    private final ObjectProvider<FileStore> fileStoreProvider;
    private final ObjectProvider<WorkflowDataRepository> workflowsProvider;
    private final ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider;
    private final Path uploadBase;
    /** Where a call works — every store call names the namespace. */
    private final ScopeSupplier scope;
    private final ai.mindconnect.common.env.EnvVarResolver environment;

    public VectorStoreService(ObjectProvider<VectorStores> storesProvider,
                              ObjectProvider<FileStore> fileStoreProvider,
                              ObjectProvider<WorkflowDataRepository> workflowsProvider,
                              ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider,
                              @Value("${mindconnect.tools.base-dir:#{systemProperties['user.home']}}")
                              String toolsBaseDir,
                              ScopeSupplier scope,
                              ObjectProvider<ai.mindconnect.common.env.EnvVarResolver> environment) {
        this.storesProvider = storesProvider;
        this.scope = scope;
        this.environment = environment.getIfAvailable(ai.mindconnect.common.env.EnvVarResolver::system);
        this.fileStoreProvider = fileStoreProvider;
        this.workflowsProvider = workflowsProvider;
        this.workflowInstancesProvider = workflowInstancesProvider;
        this.uploadBase = Path.of(toolsBaseDir).toAbsolutePath().normalize();
    }

    // ── Templates ──────────────────────────────────────────────────────────

    public List<VectorStoreTemplate> templates() {
        return stores().templates(scope.namespace());
    }

    public Optional<VectorStoreTemplate> template(String name) {
        return stores().template(scope.namespace(), name);
    }

    public void saveTemplate(VectorStoreTemplate template) {
        stores().registry(scope.namespace()).saveTemplate(template);
    }

    public void deleteTemplate(String name) {
        stores().registry(scope.namespace()).deleteTemplate(name);
    }

    // ── Indexes ────────────────────────────────────────────────────────────

    /** Where an index lives — its definition without the password, and whether it is the host's built-in one. */
    public record IndexInfo(IndexDefinition definition, boolean builtIn, String location) {}

    public List<IndexInfo> indexes() {
        VectorStores vs = stores();
        return vs.indexes(scope.namespace()).stream()
                .map(i -> new IndexInfo(i.withPassword(i.password() == null ? null : "***"),
                        vs.isBuiltIn(scope.namespace(), i.name()), location(vs, i.name())))
                .toList();
    }

    /** Saves an index definition; a missing password keeps the one stored under that name. */
    public IndexInfo saveIndex(IndexDefinition index) {
        VectorStores vs = stores();
        if (index.password() == null || "***".equals(index.password())) {
            String name = index.name();
            String kept = vs.indexes(scope.namespace()).stream().filter(i -> i.name().equals(name))
                    .map(IndexDefinition::password).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            index = index.withPassword(kept);
        }
        vs.saveIndex(scope.namespace(), index);
        return new IndexInfo(index.withPassword(index.password() == null ? null : "***"),
                false, location(vs, index.name()));
    }

    /** @return false for a built-in index the namespace did not redefine — there is nothing to delete */
    public boolean deleteIndex(String name) {
        VectorStores vs = stores();
        if (vs.isBuiltIn(scope.namespace(), name)) {
            return false;
        }
        vs.deleteIndex(scope.namespace(), name);
        return true;
    }

    private String location(VectorStores vs, String name) {
        try {
            return vs.indexLocation(scope.namespace(), name);
        } catch (RuntimeException e) {
            return "cannot be opened: " + e.getMessage();
        }
    }

    // ── Stores ─────────────────────────────────────────────────────────────

    public List<VectorStoreInstance> instances() {
        return stores().registry(scope.namespace()).instances();
    }

    public Optional<VectorStoreInstance> instance(String name) {
        return stores().registry(scope.namespace()).instance(name);
    }

    /** Registers (and creates on first use) a GLOBAL store from a template. */
    public Optional<VectorStoreInstance> createStore(String name, String template) {
        stores().open(scope.namespace(), name.trim(), template, VectorStoreInstance.Scope.GLOBAL, null);
        return stores().registry(scope.namespace()).instance(name.trim());
    }

    /**
     * Deletes the store: its documents leave the index, the stored files it
     * listed stay there for the other stores and chats that list them.
     */
    public void deleteStore(String name) {
        VectorStore store = stores().store(scope.namespace(), name);
        store.members().forEach(store::remove);
        stores().registry(scope.namespace()).deleteInstance(name);
    }

    // ── Search, entries & documents ─────────────────────────────────────────

    /** Embeds {@code query} with the store's embedding config and returns the top hits among all its entries. */
    public List<Hit> search(String storeName, String query, int topK, double minScore) {
        return search(storeName, query, topK, minScore, null, List.of());
    }

    /**
     * Embeds {@code query} with the store's embedding config and returns the
     * top hits — among the entries {@code entities} picks only ({@code null}
     * for all), passing {@code filters}.
     */
    public List<Hit> search(String storeName, String query, int topK, double minScore,
                            List<EntitySelector> entities, List<MetadataFilter> filters) {
        VectorStore store = stores().store(scope.namespace(), storeName);
        Set<EntityRef> within = entities == null ? null : store.select(entities);
        return store.search(query, topK, within, filters).stream()
                .filter(h -> h.score() >= minScore)
                .map(h -> new Hit(h.ref().type().value(), h.ref().source(), h.ref().container(), h.ref().id(),
                        h.chunk().id(), h.chunk().ordinal(), h.chunk().text(), h.chunk().metadata(), h.score()))
                .toList();
    }

    /** What the store lists, as the index has it. */
    public List<Entry> entries(String storeName) {
        return stores().store(scope.namespace(), storeName).entities().stream()
                .map(VectorStoreService::entry)
                .toList();
    }

    private static Entry entry(IndexedEntity e) {
        return new Entry(e.ref().type().value(), e.ref().source(), e.ref().container(), e.ref().id(),
                e.owner() == null ? null : e.owner().value(), e.version(), e.chunks());
    }

    /**
     * Takes the entries with id {@code entityId} off the store. A document of the
     * store leaves the index with it; a stored file stays for the other stores
     * that list it.
     *
     * @return how many were removed
     */
    public int removeEntry(String storeName, String entityId) {
        VectorStore store = stores().store(scope.namespace(), storeName);
        int removed = 0;
        for (EntityRef ref : store.members()) {
            if (ref.id().equals(entityId)) {
                store.remove(ref);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Writes a document of the store — text with no file behind it — replacing
     * what the document had; the same chunks again are not embedded again.
     */
    public UpsertedDocument upsertDocument(String storeName, String id, List<VectorStore.TextChunk> chunks) {
        VectorStore store = stores().open(scope.namespace(), storeName, null, VectorStoreInstance.Scope.GLOBAL, null);
        String owner = store.settings().owner();
        long stored = store.put(store.documentRef(id), owner == null ? null : UserId.of(owner),
                VectorTools.version(chunks), chunks);
        return new UpsertedDocument(id, stored);
    }

    // ── Ingestion ──────────────────────────────────────────────────────────

    /**
     * Adds a document to the store — THE ingestion path, shared by the UI's
     * store-page upload and the REST API. The content is spooled under the
     * tools base dir; if the store's template names an ingestion workflow it
     * runs (chunking strategy, metadata, whatever the workflow does), else
     * the built-in direct ingestion (extract → 800/400 chunking → embed →
     * upsert) applies. Returns a human-readable summary.
     */
    public String ingestUpload(String storeName, String fileName, InputStream content) throws IOException {
        return ingestUpload(storeName, fileName, null, content, null);
    }

    /**
     * Like {@link #ingestUpload(String, String, InputStream)}: with a file
     * store the upload is kept there first, as {@code uploader}'s file, and
     * enters the store as that stored file — listed under Files, indexed once,
     * attachable to chats. Without one it is a document of the store.
     */
    public String ingestUpload(String storeName, String fileName, String contentType, InputStream content,
                               UserId uploader) throws IOException {
        FileStore fs = fileStoreProvider.getIfAvailable();
        if (fs != null) {
            StoredFile stored = fs.save(fileName == null ? "upload.bin" : fileName, contentType, content, uploader);
            return ingestStoredFile(storeName, stored.id().value(), uploader);
        }
        String safeName = Path.of(fileName == null ? "upload.bin" : fileName)
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        Path dir = uploadBase.resolve("vector-store-uploads")
                .resolve(storeName.replaceAll("[^A-Za-z0-9._-]", "-"));
        Files.createDirectories(dir);
        Path target = dir.resolve(safeName);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);

        return ingest(storeName, safeName, target, null);
    }

    /**
     * The one ingestion path: a spooled file into the store — as a stored file
     * when {@code stored} is given (indexed once under its id, only listed when
     * it is indexed already), else as a document of the store named by the
     * file name.
     */
    private String ingest(String storeName, String safeName, Path target, StoredFile stored) throws IOException {
        VectorStores vs = stores();
        VectorStore store = vs.open(scope.namespace(), storeName, null, VectorStoreInstance.Scope.GLOBAL, null);
        EntityRef ref = stored != null ? EntityRef.file(stored.id()) : store.documentRef(safeName);
        if (stored != null && store.indexed(ref)) {
            store.add(ref);
            return safeName + ": already indexed, now listed in '" + storeName + "'.";
        }
        String workflowName = store.settings().ingestionWorkflow();
        if (workflowName != null && !workflowName.isBlank()) {
            var workflow = workflows().findById(workflowName).orElseThrow(() ->
                    new IllegalStateException("Ingestion workflow '" + workflowName + "' not found"));
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("file", uploadBase.relativize(target).toString());
            params.put("store", storeName);
            if (stored != null) {
                params.put("file_id", stored.id().value());
            }
            var report = new WorkflowRunService(workflowInstances(), environment.shared()::asMap)
                    .run(workflow, params);
            return safeName + ": " + summarize(report);
        }
        String text = extractText(uploadBase, target);
        String owner = store.settings().owner();
        return DirectIngestion.ingest(store, ref, owner == null ? null : UserId.of(owner),
                stored != null ? "1" : VectorTools.version(List.of(new VectorStore.TextChunk(text, Map.of()))), safeName, text);
    }

    /**
     * Like {@link #ingestUpload}, for a file already in the {@link FileStore}
     * that {@code reader} may read ({@link StoredFile#readableBy}). Anyone
     * else's file is reported like a missing one: ingesting it would put its
     * content into a store the reader can search.
     */
    public String ingestStoredFile(String storeName, String fileId, UserId reader) throws IOException {
        FileStore fs = fileStoreProvider.getIfAvailable();
        if (fs == null) throw new NotConfiguredException("File store");
        StoredFile stored = fs.find(FileId.of(fileId)).filter(file -> file.readableBy(reader)).orElseThrow(() ->
                new IllegalArgumentException("No such file: " + fileId));
        String safeName = Path.of(stored.name() == null ? "upload.bin" : stored.name())
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        Path dir = uploadBase.resolve("vector-store-uploads").resolve(storeName.replaceAll("[^A-Za-z0-9._-]", "-"));
        Files.createDirectories(dir);
        Path target = dir.resolve(safeName);
        try (InputStream content = fs.content(stored.id())) {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return ingest(storeName, safeName, target, stored);
    }

    /** Human-readable outcome of an ingestion-workflow run. */
    public static String summarize(WorkflowRunService.RunReport report) {
        if (!report.success()) {
            return "failed — " + (report.error() == null ? report.outcome().toString() : report.error());
        }
        // The seed workflow assigns vector_upsert's confirmation to 'result'.
        return report.scope() != null && report.scope().variables() != null
                ? report.scope().variables().stream()
                        .filter(v -> "result".equals(v.name()) && v.value() != null)
                        .map(WorkflowRunService.VarSnapshot::value)
                        .findFirst().orElse("ingested")
                : "ingested";
    }

    /**
     * Document extraction when the document module is present (headings stay
     * with their sections); plain UTF-8 otherwise — the same rule the
     * {@code vector_ingest_file} tool applies. The document module is an
     * optional dependency, hence the reflective guard.
     */
    private static String extractText(Path base, Path file) throws IOException {
        try {
            Class.forName("ai.mindconnect.agent.tools.document.DocumentReader");
            var model = new ai.mindconnect.agent.tools.document.DocumentReader().load(base, file);
            StringBuilder text = new StringBuilder();
            for (var section : model.sections()) {
                if (section.title() != null && !section.title().isBlank()) {
                    text.append(section.title()).append('\n');
                }
                text.append(section.content()).append("\n\n");
            }
            return text.toString();
        } catch (ClassNotFoundException e) {
            return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Text extraction failed for " + file.getFileName(), e);
        }
    }

    // ── guards ─────────────────────────────────────────────────────────────

    private VectorStores stores() {
        VectorStores vs = storesProvider.getIfAvailable();
        if (vs == null) throw new NotConfiguredException("Vector stores");
        return vs;
    }

    private WorkflowDataRepository workflows() {
        WorkflowDataRepository repo = workflowsProvider.getIfAvailable();
        if (repo == null) throw new NotConfiguredException("Workflow engine");
        return repo;
    }

    private WorkflowInstanceRepository workflowInstances() {
        WorkflowInstanceRepository repo = workflowInstancesProvider.getIfAvailable();
        if (repo == null) throw new NotConfiguredException("Workflow engine");
        return repo;
    }
}
