package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.agent.runtime.tools.attachment.ViewAttachmentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implements {@link AgentRuntime#attachFile} — the embedded twin of the
 * server's chat upload. Lives behind a {@link #createIfPresent} guard because
 * it links against the optional modules {@code mc-file-store} and
 * {@code mc-vector-store-tools}; without them the runtime simply has no
 * attach support.
 *
 * <p>Ingestion is workflow-free by default: {@code DirectIngestion} chunks
 * OpenAI-style (800/400 tokens) and embeds. When the {@code chat-uploads}
 * template names an ingestion workflow AND the workflow modules are on the
 * classpath, that workflow runs instead — same rule as the server.
 */
final class AttachSupport {

    private static final Logger log = LoggerFactory.getLogger(AttachSupport.class);

    private final Map<String, String> environment;
    private final DynamicToolActivations activations;
    private final AgentSessionRepository sessions;
    private final ai.mindconnect.filestore.FileStore fileStore;
    private final ai.mindconnect.vectorstore.tools.VectorStores stores;
    /** The host's workflow store when it has one (Postgres); null means the file store under {@code <dataBaseDir>/<namespace>/workflows}. */
    private final ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows;
    /** The namespace the runtime's stores are bound to; the fallback workflow store opens in it too. */
    private final ai.mindconnect.agent.Namespace namespace;

    private AttachSupport(Map<String, String> environment, DynamicToolActivations activations,
                          AgentSessionRepository sessions,
                          ai.mindconnect.filestore.FileStore fileStore,
                          ai.mindconnect.vectorstore.tools.VectorStores stores,
                          ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows,
                          ai.mindconnect.agent.Namespace namespace) {
        this.environment = environment;
        this.activations = activations;
        this.sessions = sessions;
        this.fileStore = fileStore;
        this.stores = stores;
        this.workflows = workflows;
        this.namespace = namespace;
    }

    /** Null when the optional file/vector modules are not on the classpath. */
    static AttachSupport createIfPresent(Map<String, String> environment,
                                         DynamicToolActivations activations,
                                         AgentSessionRepository sessions,
                                         LlmEmbeddings embeddings,
                                         LlmConfigRepository llmConfigs,
                                         ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows,
                                         ai.mindconnect.filestore.FileStore hostFileStore,
                                         ai.mindconnect.agent.Namespace namespace) {
        try {
            Class.forName("ai.mindconnect.filestore.FileStoreBackend");
            Class.forName("ai.mindconnect.vectorstore.tools.VectorStores");
        } catch (ClassNotFoundException e) {
            return null;
        }
        return create(environment, activations, sessions, embeddings, llmConfigs, workflows, hostFileStore, namespace);
    }

    /**
     * The filesystem file store under the data dir when the file-store module
     * is on the classpath, {@code null} otherwise — the builder feeds it to
     * the message mapper before any tool support exists.
     */
    static ai.mindconnect.filestore.FileStore defaultFileStoreIfPresent(Map<String, String> environment,
                                                                        ai.mindconnect.agent.Namespace namespace) {
        try {
            Class.forName("ai.mindconnect.filestore.filesystem.FilesystemFileStoreBackend");
        } catch (ClassNotFoundException e) {
            return null;
        }
        return openDefaultFileStore(environment, namespace);
    }

    /** Separate method so the backend type is only linked once the guard passed. */
    private static ai.mindconnect.filestore.FileStore openDefaultFileStore(Map<String, String> environment,
                                                                           ai.mindconnect.agent.Namespace namespace) {
        return ai.mindconnect.filestore.FileStoreBackend
                .byType(environment.getOrDefault("fileStoreBackend", "filesystem"))
                .orElseThrow()
                .open(Map.of("baseDir", environment.get("dataBaseDir"), "namespace", namespace.value()));
    }

    /** Separate method so optional types are only linked once the guard passed. */
    private static AttachSupport create(Map<String, String> environment,
                                        DynamicToolActivations activations,
                                        AgentSessionRepository sessions,
                                        LlmEmbeddings embeddings,
                                        LlmConfigRepository llmConfigs,
                                         ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows,
                                        ai.mindconnect.filestore.FileStore hostFileStore,
                                        ai.mindconnect.agent.Namespace namespace) {
        var fileStore = hostFileStore != null ? hostFileStore : openDefaultFileStore(environment, namespace);
        var env = new ai.mindconnect.agent.tool.ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) embeddings);
                if (type == LlmConfigRepository.class) return Optional.of((T) llmConfigs);
                if (type == ai.mindconnect.agent.Namespace.class) return Optional.of((T) namespace);
                return Optional.empty();
            }
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(environment.get(key)).filter(s -> !s.isBlank());
            }
        };
        var stores = ai.mindconnect.vectorstore.tools.VectorStores.fromEnvironment(env)
                .orElse(null);
        if (stores == null) {
            return null;
        }
        return new AttachSupport(environment, activations, sessions, fileStore, stores, workflows, namespace);
    }

    String attach(SessionId sessionId, String fileName, InputStream content) {
        try {
            return attachStored(sessionId, fileStore.save(fileName, null, content));
        } catch (Exception e) {
            throw new IllegalStateException("attachFile failed: " + e.getMessage(), e);
        }
    }

    /** The store uploads land in; exposed so protocol backends can upload without ingesting. */
    ai.mindconnect.filestore.FileStore fileStore() {
        return fileStore;
    }

    /** The users' home the runtime keeps session directories under — the copy of an upload goes there. */
    private ai.mindconnect.agent.runtime.service.UserHome userHome() {
        return AgentRuntimeBuilder.userHomeOf(environment, namespace.value());
    }

    /**
     * Ingests an ALREADY-STORED file into the session's vector store and
     * activates {@code vector_search} — the second half of {@link #attach},
     * for callers that upload first (protocol {@code Files.upload}) and
     * reference later ({@code Document(FileId)} content parts).
     */
    String attachStored(SessionId sessionId, ai.mindconnect.filestore.StoredFile stored) {
        var attached = new AttachedFile(
                stored.id().value(), stored.name(), stored.contentType(), stored.size());
        var session = sessions.findById(sessionId).orElseThrow(() ->
                new IllegalArgumentException("Unknown session " + sessionId.value()));
        if (attached.isImage()) {
            // Not text to index: the image goes to the model with the next
            // message as an image part, or as that part's placeholder. Shown
            // once; afterwards the model asks for it through view_attachment.
            sessions.update(sessionId, session -> session.withAttachedFiles(List.of(attached)));
            activations.activate(sessionId,
                    List.of(ViewAttachmentTool.NAME));
            return stored.name() + " attached — it goes to the model with the next message.";
        }
        try {
            String storeName = "session-" + sessionId.value();
            var template = stores.template("chat-uploads").orElseGet(() -> {
                var created = new ai.mindconnect.vectorstore.tools.VectorStoreTemplate(
                        "chat-uploads", environment.getOrDefault("vectorStoreBackend", "memory"),
                        Map.of(), environment.getOrDefault("vectorStoreEmbeddingConfig", "embeddings"),
                        null, Map.of("description", "Per-chat-session upload stores (auto-created)"));
                stores.registry().saveTemplate(created);
                return created;
            });
            var chat = sessions.findById(sessionId).orElseThrow(() ->
                    new IllegalArgumentException("unknown session " + sessionId.value()));
            // The store is the chat's user's: the vector tools reach it only on that user's behalf.
            var store = stores.open(storeName, template.name(),
                    ai.mindconnect.vectorstore.tools.VectorStoreInstance.Scope.SESSION,
                    sessionId.value(), chat.userId() == null ? null : chat.userId().value());
            var instance = stores.settingsFor(storeName);
            // A copy in the session's own directory, for the file tools and
            // for the ingestion workflow, which runs in the session's scope.
            java.util.Optional<java.nio.file.Path> copy = userHome()
                    .uploadsDirOf(session.userId(), sessionId).map(dir -> dir.resolve(stored.name()));
            if (copy.isPresent()) {
                try (InputStream in = fileStore.content(stored.id())) {
                    java.nio.file.Files.copy(in, copy.get(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                attached = attached.withPath(copy.get().toString());
            }
            String message;
            if (instance.ingestionWorkflow() != null && !instance.ingestionWorkflow().isBlank()
                    && workflowModulesPresent()) {
                message = WorkflowIngestion.run(environment, stores, instance, stored, fileStore, workflows,
                        namespace.value(), chat, copy.orElse(null));
            } else {
                String text = new String(fileStore.content(stored.id()).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                message = ai.mindconnect.vectorstore.tools.DirectIngestion.ingest(
                        stores, store, storeName, stored.name(), text);
            }

            activations.activate(sessionId, attached.isPdf()
                    ? List.of("vector_search", ViewAttachmentTool.NAME)
                    : List.of("vector_search"));
            // The prompt names the copy's path, so the file tools must reach
            // it: a session working in a project gets its own directory (the
            // uploads' parent) as an additional one — a no-op when it works
            // in there already. Recorded in one change, under the store's lock.
            var recorded = attached;
            Optional<String> ownDir = copy.map(c -> c.getParent().getParent().toString());
            sessions.update(sessionId, current -> {
                var updated = current.withAttachedFiles(List.of(recorded));
                return ownDir.map(updated::withAdditionalDir).orElse(updated);
            });
            return message;
        } catch (Exception e) {
            throw new IllegalStateException("attachFile failed: " + e.getMessage(), e);
        }
    }

    private static boolean workflowModulesPresent() {
        try {
            Class.forName("ai.mindconnect.workflow.admin.run.WorkflowRunService");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Workflow path, isolated so its optional types link only when used. */
    private static final class WorkflowIngestion {
        static String run(Map<String, String> environment,
                          ai.mindconnect.vectorstore.tools.VectorStores stores,
                          ai.mindconnect.vectorstore.tools.VectorStoreInstance instance,
                          ai.mindconnect.filestore.StoredFile stored,
                          ai.mindconnect.filestore.FileStore fileStore,
                          ai.mindconnect.workflow.persistence.port.WorkflowDataRepository hostWorkflows,
                          String partition,
                          ai.mindconnect.agent.runtime.domain.AgentSession chat,
                          java.nio.file.Path copyInSessionDir) throws Exception {
            var workflows = hostWorkflows != null ? hostWorkflows
                    : new ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository(
                            java.nio.file.Path.of(environment.get("dataBaseDir")), partition);
            var workflow = workflows.findById(instance.ingestionWorkflow()).orElseThrow(() ->
                    new IllegalStateException("Ingestion workflow '" + instance.ingestionWorkflow()
                            + "' not found in the workflow store"));
            var runner = new ai.mindconnect.workflow.admin.run.WorkflowRunService(null);
            ai.mindconnect.workflow.admin.run.WorkflowRunService.RunReport report;
            // The tool steps run on behalf of the chat's user and session — the
            // calls its store accepts — and resolve their paths in its directories.
            if (copyInSessionDir != null) {
                var scope = ai.mindconnect.agent.tool.ToolCallScope.ofSession(
                        chat.userId(), chat.id(), copyInSessionDir.getParent().toString());
                report = scope.runWith(() -> runner.runWithAttributes(workflow,
                        Map.of("file", stored.name(), "store", instance.name()),
                        Map.of(ai.mindconnect.agent.tool.ToolCallScope.class.getName(), scope)));
            } else {
                java.nio.file.Path base = java.nio.file.Path.of(
                        environment.getOrDefault("defaultBaseDir", System.getProperty("user.home")));
                java.nio.file.Path dir = base.resolve("vector-store-uploads").resolve(instance.name());
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path target = dir.resolve(stored.name());
                try (InputStream in = fileStore.content(stored.id())) {
                    java.nio.file.Files.copy(in, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                report = runner.runWithAttributes(workflow,
                        Map.of("file", base.relativize(target).toString(), "store", instance.name()),
                        Map.of(ai.mindconnect.agent.tool.ToolCallScope.class.getName(),
                                new ai.mindconnect.agent.tool.ToolCallScope(chat.userId(), chat.id(), null)));
            }
            if (!report.success()) {
                throw new IllegalStateException("ingestion workflow failed: " + report.error());
            }
            return stored.name() + " ingested via workflow '" + instance.ingestionWorkflow() + "'.";
        }
    }
}
