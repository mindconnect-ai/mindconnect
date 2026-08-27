package ai.mindconnect.agentrest.service;

import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attaches a stored file to a chat session — the one code path shared by the
 * external REST endpoint and the chat UI: ensure the {@code chat-uploads}
 * template, open the session's SESSION-scoped store, spool the content from
 * the {@link FileStore} (backend-agnostic) under the tools base dir, run the
 * template's ingestion workflow, and activate {@code vector_search} for the
 * session — persisted on the session, so the agent keeps the tool across
 * restarts.
 *
 * <p>Vector stores and the workflow engine are optional in a host
 * application; attach reports a failed {@link AttachResult} (and the other
 * operations throw {@link NotConfiguredException}) when the capability is
 * absent instead of failing the context at startup.
 */
@Service
public class SessionFileService {

    public static final String CHAT_UPLOADS_TEMPLATE = "chat-uploads";

    public record AttachResult(StoredFile file, String store, boolean success, String message) {}

    private final FileStore fileStore;
    private final ObjectProvider<VectorStores> storesProvider;
    private final ObjectProvider<DynamicToolActivations> activationsProvider;
    private final AgentSessionRepository sessions;
    private final ObjectProvider<WorkflowDataRepository> workflowsProvider;
    private final ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider;
    private final Path spoolBase;

    public SessionFileService(FileStore fileStore,
                              ObjectProvider<VectorStores> storesProvider,
                              ObjectProvider<DynamicToolActivations> activationsProvider,
                              AgentSessionRepository sessions,
                              ObjectProvider<WorkflowDataRepository> workflowsProvider,
                              ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider,
                              @Value("${mindconnect.tools.base-dir:#{systemProperties['user.home']}}") String toolsBaseDir) {
        this.fileStore = fileStore;
        this.storesProvider = storesProvider;
        this.activationsProvider = activationsProvider;
        this.sessions = sessions;
        this.workflowsProvider = workflowsProvider;
        this.workflowInstancesProvider = workflowInstancesProvider;
        this.spoolBase = Path.of(toolsBaseDir).toAbsolutePath().normalize();
    }

    /** The session's attached files: spooled file id → chunk count. Empty when none. */
    public Map<String, Long> listAttachments(UUID sessionId) {
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores == null) return Map.of();
        String storeName = "session-" + sessionId;
        try {
            if (stores.registry().instance(storeName).isPresent()) {
                return stores.openWith(stores.settingsFor(storeName)).listFiles();
            }
        } catch (RuntimeException ignored) {
            // Store unreadable — report "no attachments" rather than break the chat.
        }
        return Map.of();
    }

    public void deleteAttachment(UUID sessionId, String fileId) {
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores == null) throw new NotConfiguredException("Vector stores");
        String storeName = "session-" + sessionId;
        stores.openWith(stores.settingsFor(storeName)).deleteFile(fileId);
        Path spooled = spoolBase.resolve(fileId).normalize();
        if (spooled.startsWith(spoolBase)) {
            try {
                Files.deleteIfExists(spooled);
            } catch (java.io.IOException ignored) {
                // The searchable chunks are gone; a stale spool file is harmless.
            }
        }
        String fileName = Path.of(fileId).getFileName().toString();
        sessions.findById(sessionId).ifPresent(session ->
                sessions.save(session.withoutAttachedFile(fileName)));
    }

    public AttachResult attach(UUID sessionId, StoredFile stored) {
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores == null) {
            return new AttachResult(stored, null, false,
                    stored.name() + ": vector stores are not configured in this application.");
        }
        String storeName = "session-" + sessionId;
        VectorStoreTemplate template = stores.template(CHAT_UPLOADS_TEMPLATE).orElseGet(() -> {
            VectorStoreTemplate created = new VectorStoreTemplate(CHAT_UPLOADS_TEMPLATE,
                    "memory", Map.of(), "embeddings", "file-ingestion",
                    Map.of("description", "Per-chat-session upload stores (auto-created)"));
            stores.registry().saveTemplate(created);
            return created;
        });
        stores.open(storeName, template.name(), VectorStoreInstance.Scope.SESSION, sessionId.toString());
        VectorStoreInstance instance = stores.settingsFor(storeName);

        try {
            if (instance.ingestionWorkflow() == null || instance.ingestionWorkflow().isBlank()) {
                // No workflow on the template: the built-in default ingestion —
                // OpenAI-style 800/400-token chunking straight from the stream.
                String text;
                try (InputStream content = fileStore.content(stored.id())) {
                    text = new String(content.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                DirectIngestion.ingest(stores, stores.openWith(instance), storeName, stored.name(), text);
            } else {
                WorkflowDataRepository workflows = workflowsProvider.getIfAvailable();
                WorkflowInstanceRepository workflowInstances = workflowInstancesProvider.getIfAvailable();
                if (workflows == null || workflowInstances == null) {
                    return new AttachResult(stored, storeName, false,
                            stored.name() + ": the workflow engine is not configured in this application.");
                }
                Path dir = spoolBase.resolve("vector-store-uploads").resolve(storeName);
                Files.createDirectories(dir);
                Path target = dir.resolve(stored.name());
                try (InputStream content = fileStore.content(stored.id())) {
                    Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
                }
                var workflow = workflows.findById(instance.ingestionWorkflow()).orElseThrow(() ->
                        new IllegalStateException("Ingestion workflow '" + instance.ingestionWorkflow()
                                + "' not found"));
                var report = new WorkflowRunService(workflowInstances)
                        .run(workflow, Map.of("file", spoolBase.relativize(target).toString(),
                                "store", storeName));
                if (!report.success()) {
                    return new AttachResult(stored, storeName, false,
                            stored.name() + ": ingestion failed — " + report.error());
                }
            }
            // The agent gets vector_search in this session from the next round
            // on (persisted on the session), defaulting to exactly this store.
            DynamicToolActivations activations = activationsProvider.getIfAvailable();
            if (activations != null) {
                activations.activate(sessionId, List.of("vector_search"));
            }
            // Announce the file in the system prompt (rendered fresh each
            // round) so the model actually reaches for vector_search.
            sessions.findById(sessionId).ifPresent(session ->
                    sessions.save(session.withAttachedFiles(List.of(stored.name()))));
            return new AttachResult(stored, storeName, true,
                    stored.name() + " attached — the agent can now search it.");
        } catch (Exception e) {
            return new AttachResult(stored, storeName, false,
                    stored.name() + ": " + e.getMessage());
        }
    }
}
