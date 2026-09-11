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
import ai.mindconnect.agent.tool.ToolCallScope;

/**
 * Attaches a stored file to a chat session — the one code path shared by the
 * external REST endpoint and the chat UI: ensure the {@code chat-uploads}
 * template, open the session's SESSION-scoped store, put a copy of the
 * content from the {@link FileStore} (backend-agnostic) into the session's
 * own directory — where the file tools read it — run the template's
 * ingestion workflow in the session's scope, and activate
 * {@code vector_search}, {@code file_read} and {@code file_list} for the
 * session — persisted on the session, so the agent keeps the tools across
 * restarts. Without a users' home the copy goes under the tools base dir, as
 * it always did.
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
    /** Where a session's own directory is; unconfigured means the spool under the tools base dir. */
    private final ai.mindconnect.agent.runtime.service.UserHome userHome;

    public SessionFileService(FileStore fileStore,
                              ObjectProvider<VectorStores> storesProvider,
                              ObjectProvider<DynamicToolActivations> activationsProvider,
                              AgentSessionRepository sessions,
                              ObjectProvider<WorkflowDataRepository> workflowsProvider,
                              ObjectProvider<WorkflowInstanceRepository> workflowInstancesProvider,
                              @Value("${mindconnect.tools.base-dir:#{systemProperties['user.home']}}") String toolsBaseDir,
                              ObjectProvider<ai.mindconnect.agent.runtime.service.UserHome> userHome) {
        this.fileStore = fileStore;
        this.storesProvider = storesProvider;
        this.activationsProvider = activationsProvider;
        this.sessions = sessions;
        this.workflowsProvider = workflowsProvider;
        this.workflowInstancesProvider = workflowInstancesProvider;
        this.spoolBase = Path.of(toolsBaseDir).toAbsolutePath().normalize();
        this.userHome = userHome.getIfAvailable(ai.mindconnect.agent.runtime.service.UserHome::none);
    }

    /** The copy of an attached file on disk: in the session's uploads directory when there is one. */
    private java.util.Optional<Path> uploadsDir(ai.mindconnect.agent.runtime.domain.AgentSession session) {
        return userHome.uploadsDirOf(session.userId(), session.id());
    }

    /**
     * Puts the content next to the session's other uploads and answers where
     * it landed — empty when the session has no directory of its own. Every
     * attached file goes through here, images included: whatever the model
     * does with a file, the file tools can open it by that path.
     */
    private java.util.Optional<Path> copyIntoUploads(AgentSession session, StoredFile stored) throws java.io.IOException {
        java.util.Optional<Path> target = uploadsDir(session).map(dir -> dir.resolve(stored.name()));
        if (target.isPresent()) {
            try (InputStream content = fileStore.content(stored.id())) {
                Files.copy(content, target.get(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    /**
     * Records the file on the session and, when the copy landed in the
     * session's own directory, that directory as an additional one — the
     * system prompt names the copy's path, so the file tools must be allowed
     * to open it. Both under one lock; false when the session is gone.
     */
    private boolean record(SessionId sessionId, AttachedFile attached, java.util.Optional<Path> copy) {
        java.util.Optional<String> ownDir = copy.map(c -> c.getParent().getParent().toString());
        return sessions.update(sessionId, current -> {
            AgentSession updated = current.withAttachedFiles(List.of(attached));
            return ownDir.map(updated::withAdditionalDir).orElse(updated);
        }).isPresent();
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
     * keys by). Its chunks leave the session's vector store and the copy in
     * the session's directory goes with them; an image, never ingested, loses
     * that copy and its entry on the session. The original in the file store
     * is untouched.
     */
    public void deleteAttachment(SessionId sessionId, String fileIdOrName) {
        String fileName = Path.of(fileIdOrName).getFileName().toString();
        VectorStores stores = storesProvider.getIfAvailable();
        if (stores != null) {
            String storeName = "session-" + sessionId.value();
            for (String ingestedId : listAttachments(sessionId).keySet()) {
                if (!Path.of(ingestedId).getFileName().toString().equals(fileName)) continue;
                stores.openWith(stores.settingsFor(storeName)).deleteFile(ingestedId);
                // A copy spooled under the tools base dir the old way carries
                // its directory in the key; a bare name is a copy in the
                // session's directory, removed below — never a file of that
                // name in the base dir.
                Path spooled = spoolBase.resolve(ingestedId).normalize();
                if (ingestedId.startsWith("vector-store-uploads/") && spooled.startsWith(spoolBase)) {
                    try {
                        Files.deleteIfExists(spooled);
                    } catch (java.io.IOException ignored) {
                        // The searchable chunks are gone; a stale spool file is harmless.
                    }
                }
            }
        }
        sessions.findById(sessionId).ifPresent(session -> uploadsDir(session).ifPresent(dir -> {
            try {
                Files.deleteIfExists(dir.resolve(fileName));
            } catch (java.io.IOException ignored) {
                // The record and the chunks are gone; a stale copy is harmless.
            }
        }));
        sessions.update(sessionId, session -> session.withoutAttachedFile(fileName));
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

    /**
     * The copy on disk is only reachable if the agent has the tools to reach
     * it. An agent that finds its tools by searching would have to search
     * first, while {@code vector_search} sits right in front of it from the
     * ingest — so an upload activates the two file tools with it, and the
     * prompt's "open it by that path" is advice the agent can follow.
     */
    private void activateFileTools(SessionId sessionId) {
        DynamicToolActivations activations = activationsProvider.getIfAvailable();
        if (activations != null) {
            activations.activate(sessionId, List.of("file_read", "file_list"));
        }
    }

    /**
     * Attaches a stored file to the session: every file is copied into the
     * session's directory, and everything but an image is also ingested into
     * its vector store. A failure comes back as the result — the chat shows it as
     * a toast — and is logged, since the toast is gone once it fades.
     */
    public AttachResult attach(SessionId sessionId, StoredFile stored) {
        AttachResult result = ingest(sessionId, stored);
        if (!result.success()) {
            log.warn("Attaching '{}' to session {} failed: {}", stored.name(), sessionId.value(), result.message());
        }
        return result;
    }

    private AttachResult ingest(SessionId sessionId, StoredFile stored) {
        AttachedFile attached = new AttachedFile(stored.id().value(), stored.name(), stored.contentType(), stored.size());
        var sessionOpt = sessions.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return new AttachResult(stored, null, false, stored.name() + ": unknown session " + sessionId);
        }
        var session = sessionOpt.get();
        if (attached.isImage()) {
            // An image is not text to index: it goes to the model with the
            // next message as an image part — or as that part's placeholder
            // when the model does not read images. It is still a file the
            // user put in this chat, so it is copied into the session's
            // uploads directory like any other: the file tools list it, and
            // an agent that writes code can point at it by path. A failed
            // copy costs the path, not the attachment.
            java.util.Optional<Path> copy = java.util.Optional.empty();
            try {
                copy = copyIntoUploads(session, stored);
            } catch (Exception e) {
                log.warn("Copying image '{}' into the directory of session {} failed, attaching it without a path: {}",
                        stored.name(), sessionId.value(), e.getMessage());
            }
            final AttachedFile bare = attached;
            AttachedFile image = copy.map(c -> bare.withPath(c.toString())).orElse(bare);
            if (!record(sessionId, image, copy)) {
                return new AttachResult(stored, null, false, stored.name() + ": unknown session " + sessionId);
            }
            activateViewer(sessionId);
            if (copy.isPresent()) activateFileTools(sessionId);
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
        // The store is the chat's user's: the vector tools reach it only on that user's behalf.
        stores.open(storeName, template.name(), VectorStoreInstance.Scope.SESSION, sessionId.value(),
                session.userId() == null ? null : session.userId().value());
        VectorStoreInstance instance = stores.settingsFor(storeName);

        try {
            // A copy in the session's own directory: the file tools read it
            // there, and the ingestion workflow below runs against it.
            java.util.Optional<Path> copy = copyIntoUploads(session, stored);
            if (copy.isPresent()) {
                attached = attached.withPath(copy.get().toString());
            }
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
                var workflow = workflows.findById(instance.ingestionWorkflow()).orElseThrow(() ->
                        new IllegalStateException("Ingestion workflow '" + instance.ingestionWorkflow()
                                + "' not found"));
                var runner = new WorkflowRunService(workflowInstances);
                ai.mindconnect.workflow.admin.run.WorkflowRunService.RunReport report;
                // The workflow's tool steps run on behalf of this chat's user and
                // session — the calls its upload store accepts — and resolve their
                // paths in the session's own directories.
                if (copy.isPresent()) {
                    ToolCallScope scope = ToolCallScope.ofSession(
                            session.userId(), sessionId, copy.get().getParent().toString());
                    report = scope.runWith(() -> runner.runWithAttributes(workflow,
                            Map.of("file", stored.name(), "store", storeName),
                            Map.of(ToolCallScope.class.getName(), scope)));
                } else {
                    Path dir = spoolBase.resolve("vector-store-uploads").resolve(storeName);
                    Files.createDirectories(dir);
                    Path target = dir.resolve(stored.name());
                    try (InputStream content = fileStore.content(stored.id())) {
                        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    report = runner.runWithAttributes(workflow,
                            Map.of("file", spoolBase.relativize(target).toString(), "store", storeName),
                            Map.of(ToolCallScope.class.getName(),
                                    new ToolCallScope(session.userId(), sessionId, null)));
                }
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
            if (copy.isPresent()) activateFileTools(sessionId);
            if (attached.isPdf()) activateViewer(sessionId);
            // Announce the file in the system prompt (rendered fresh each
            // round) so the model reaches for it at all — and give the session
            // its own directory (the uploads' parent) as an additional one, so
            // the path the prompt names is a path the file tools may open.
            record(sessionId, attached, copy);
            return new AttachResult(stored, storeName, true,
                    stored.name() + " attached — the agent can now search it.");
        } catch (Exception e) {
            log.debug("Attaching '{}' to session {} threw", stored.name(), sessionId.value(), e);
            return new AttachResult(stored, storeName, false,
                    stored.name() + ": " + e.getMessage());
        }
    }
}
