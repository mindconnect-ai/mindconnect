package ai.mindconnect.agentrest.service;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.tools.DirectIngestion;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
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
import java.util.UUID;

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

    /** One search result — the chunk's content and score, without the raw embedding. */
    public record Hit(String id, String fileId, int ordinal, String text,
                      Map<String, String> metadata, double score) {}

    public record UpsertedChunk(String id, int dimension) {}

    private final ObjectProvider<VectorStores> storesProvider;
    private final ObjectProvider<FileStore> fileStoreProvider;
    private final ObjectProvider<WorkflowDataRepository> workflowsProvider;
    private final ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider;
    private final Path uploadBase;

    public VectorStoreService(ObjectProvider<VectorStores> storesProvider,
                              ObjectProvider<FileStore> fileStoreProvider,
                              ObjectProvider<WorkflowDataRepository> workflowsProvider,
                              ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider,
                              @Value("${mindconnect.tools.base-dir:#{systemProperties['user.home']}}")
                              String toolsBaseDir) {
        this.storesProvider = storesProvider;
        this.fileStoreProvider = fileStoreProvider;
        this.workflowsProvider = workflowsProvider;
        this.workflowInstancesProvider = workflowInstancesProvider;
        this.uploadBase = Path.of(toolsBaseDir).toAbsolutePath().normalize();
    }

    // ── Templates ──────────────────────────────────────────────────────────

    public List<VectorStoreTemplate> templates() {
        return stores().templates();
    }

    public Optional<VectorStoreTemplate> template(String name) {
        return stores().template(name);
    }

    public void saveTemplate(VectorStoreTemplate template) {
        stores().registry().saveTemplate(template);
    }

    public void deleteTemplate(String name) {
        stores().registry().deleteTemplate(name);
    }

    // ── Stores ─────────────────────────────────────────────────────────────

    public List<VectorStoreInstance> instances() {
        return stores().registry().instances();
    }

    public Optional<VectorStoreInstance> instance(String name) {
        return stores().registry().instance(name);
    }

    /** Registers (and creates on first use) a GLOBAL store from a template. */
    public Optional<VectorStoreInstance> createStore(String name, String template) {
        stores().open(name.trim(), template, VectorStoreInstance.Scope.GLOBAL, null);
        return stores().registry().instance(name.trim());
    }

    public void deleteStore(String name) {
        stores().registry().deleteInstance(name);
    }

    // ── Search & chunks ────────────────────────────────────────────────────

    /** Embeds {@code query} with the store's embedding config and returns the top hits. */
    public List<Hit> search(String storeName, String query, int topK, double minScore) {
        VectorStores vs = stores();
        float[] embedding = vs.embedFor(storeName, List.of(query)).get(0);
        return vs.openWith(vs.settingsFor(storeName)).search(embedding, topK).stream()
                .filter(h -> h.score() >= minScore)
                .map(h -> new Hit(h.chunk().id(), h.chunk().fileId(), h.chunk().ordinal(),
                        h.chunk().text(), h.chunk().metadata(), h.score()))
                .toList();
    }

    /** Embeds {@code text} and upserts it as a single chunk. */
    public UpsertedChunk upsertChunk(String storeName, String id, String fileId,
                                     Integer ordinal, String text, Map<String, String> metadata) {
        VectorStores vs = stores();
        String effectiveFileId = fileId != null && !fileId.isBlank() ? fileId : "api";
        String effectiveId = id != null && !id.isBlank() ? id
                : effectiveFileId + ":" + UUID.randomUUID();
        float[] embedding = vs.embedFor(storeName, List.of(text)).get(0);
        vs.openWith(vs.settingsFor(storeName)).upsert(List.of(new VectorChunk(
                effectiveId, effectiveFileId, ordinal != null ? ordinal : 0, text,
                metadata == null ? Map.of() : metadata, embedding)));
        return new UpsertedChunk(effectiveId, embedding.length);
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
        VectorStores vs = stores();
        String safeName = Path.of(fileName == null ? "upload.bin" : fileName)
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        Path dir = uploadBase.resolve("vector-store-uploads")
                .resolve(storeName.replaceAll("[^A-Za-z0-9._-]", "-"));
        Files.createDirectories(dir);
        Path target = dir.resolve(safeName);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);

        VectorStoreInstance instance = vs.settingsFor(storeName);
        String workflowName = instance.ingestionWorkflow();
        if (workflowName != null && !workflowName.isBlank()) {
            var workflow = workflows().findById(workflowName).orElseThrow(() ->
                    new IllegalStateException("Ingestion workflow '" + workflowName + "' not found"));
            var report = new WorkflowRunService(workflowInstances())
                    .run(workflow, Map.of("file", uploadBase.relativize(target).toString(),
                            "store", storeName));
            return safeName + ": " + summarize(report);
        }
        String text = extractText(uploadBase, target);
        return DirectIngestion.ingest(vs, vs.openWith(instance), storeName, safeName, text);
    }

    /** Like {@link #ingestUpload}, for a file already in the {@link FileStore}. */
    public String ingestStoredFile(String storeName, String fileId) throws IOException {
        FileStore fs = fileStoreProvider.getIfAvailable();
        if (fs == null) throw new NotConfiguredException("File store");
        StoredFile stored = fs.find(fileId).orElseThrow(() ->
                new IllegalArgumentException("No such file: " + fileId));
        try (InputStream content = fs.content(stored.id())) {
            return ingestUpload(storeName, stored.name(), content);
        }
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
