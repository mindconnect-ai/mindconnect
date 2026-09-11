package ai.mindconnect.agentrest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.agent.runtime.tools.attachment.ViewAttachmentTool;
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
import ai.mindconnect.agent.SessionId;

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

    private static final Logger log = LoggerFactory.getLogger(SessionFileService.class);

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

    /**
     * The files attached to the session, in attach order — the session's own
     * record, images included. {@link #listAttachments} adds the searchable
     * chunks each ingested file produced.
     */
    public List<AttachedFile> attachments(SessionId sessionId) {
        return sessions.findById(sessionId).map(AgentSession::attachedFiles).orElse(List.of());
    }

    /** The session's ingested files: spooled file id → chunk count. Empty when none. */
    public Map<String, Long> listAttachments(SessionId sessionId) {
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores == null) return Map.of();
        String storeName = "session-" + sessionId.value();
        try {
            if (stores.registry().instance(storeName).isPresent()) {
                return stores.openWith(stores.settingsFor(storeName)).listFiles();
            }
        } catch (RuntimeException e) {
            // Store unreadable — report "no attachments" rather than break the chat,
            // but say so: an empty list alone looks like data that was never there.
            log.warn("Vector store {} is unreadable, listing no attachments: {}", storeName, e.getMessage());
        }
        return Map.of();
    }

    /**
     * Detaches a file from the chat, named by its file name or by the id its
     * chunks were ingested under (the spooled path — what {@link #listAttachments}
     * keys by). Its chunks leave the session's vector store and the spooled
     * copy goes with them; an image, never ingested, simply leaves the
     * session's record. The original in the file store is untouched.
     */
    public void deleteAttachment(SessionId sessionId, String fileIdOrName) {
        String fileName = Path.of(fileIdOrName).getFileName().toString();
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores != null) {
            String storeName = "session-" + sessionId.value();
            for (String ingestedId : listAttachments(sessionId).keySet()) {
                if (!Path.of(ingestedId).getFileName().toString().equals(fileName)) continue;
                stores.openWith(stores.settingsFor(storeName)).deleteFile(ingestedId);
                Path spooled = spoolBase.resolve(ingestedId).normalize();
                if (spooled.startsWith(spoolBase)) {
                    try {
                        Files.deleteIfExists(spooled);
                    } catch (java.io.IOException ignored) {
                        // The searchable chunks are gone; a stale spool file is harmless.
                    }
                }
            }
        }
        sessions.findById(sessionId).ifPresent(session ->
                sessions.save(session.withoutAttachedFile(fileName)));
    }

    /**
     * A file the model may read inline is shown with the next message only;
     * afterwards the model asks for it again through {@code view_attachment}
     * — activated for the session, like {@code vector_search} on ingest.
     */
    private void activateViewer(SessionId sessionId) {
        DynamicToolActivations activations = activationsProvider.getIfAvailable();
        if (activations != null) {
            activations.activate(sessionId,
                    List.of(ViewAttachmentTool.NAME));
        }
    }

    public AttachResult attach(SessionId sessionId, StoredFile stored) {
        AttachedFile attached = new AttachedFile(stored.id().value(), stored.name(), stored.contentType(), stored.size());
        if (attached.isImage()) {
            // An image is not text to index: it goes to the model with the
            // next message as an image part — or as that part's placeholder
            // when the model does not read images. Recorded on the session,
            // nothing else to do.
            if (sessions.findById(sessionId).isEmpty()) {
                return new AttachResult(stored, null, false, stored.name() + ": unknown session " + sessionId);
            }
            sessions.findById(sessionId).ifPresent(session ->
                    sessions.save(session.withAttachedFiles(List.of(attached))));
            activateViewer(sessionId);
            return new AttachResult(stored, null, true,
                    stored.name() + " attached — it goes to the model with your next message.");
        }
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores == null) {
            return new AttachResult(stored, null, false,
                    stored.name() + ": vector stores are not configured in this application.");
        }
        String storeName = "session-" + sessionId.value();
        VectorStoreTemplate template = stores.template(CHAT_UPLOADS_TEMPLATE).orElseGet(() -> {
            VectorStoreTemplate created = new VectorStoreTemplate(CHAT_UPLOADS_TEMPLATE,
                    "memory", Map.of(), "embeddings", "file-ingestion",
                    Map.of("description", "Per-chat-session upload stores (auto-created)"));
            stores.registry().saveTemplate(created);
            return created;
        });
        stores.open(storeName, template.name(), VectorStoreInstance.Scope.SESSION, sessionId.value());
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
            if (attached.isPdf()) activateViewer(sessionId);
            // Announce the file in the system prompt (rendered fresh each
            // round) so the model actually reaches for vector_search.
            sessions.findById(sessionId).ifPresent(session ->
                    sessions.save(session.withAttachedFiles(List.of(attached))));
            return new AttachResult(stored, storeName, true,
                    stored.name() + " attached — the agent can now search it.");
        } catch (Exception e) {
            return new AttachResult(stored, storeName, false,
                    stored.name() + ": " + e.getMessage());
        }
    }
}
