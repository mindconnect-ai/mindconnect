package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    /** The host's workflow store when it has one (Postgres); null means the file store under {@code workflowDir}. */
    private final ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows;

    private AttachSupport(Map<String, String> environment, DynamicToolActivations activations,
                          AgentSessionRepository sessions,
                          ai.mindconnect.filestore.FileStore fileStore,
                          ai.mindconnect.vectorstore.tools.VectorStores stores,
                          ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows) {
        this.environment = environment;
        this.activations = activations;
        this.sessions = sessions;
        this.fileStore = fileStore;
        this.stores = stores;
        this.workflows = workflows;
    }

    /** Null when the optional file/vector modules are not on the classpath. */
    static AttachSupport createIfPresent(Map<String, String> environment,
                                         DynamicToolActivations activations,
                                         AgentSessionRepository sessions,
                                         LlmEmbeddings embeddings,
                                         LlmConfigRepository llmConfigs,
                                         ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows) {
        try {
            Class.forName("ai.mindconnect.filestore.FileStoreBackend");
            Class.forName("ai.mindconnect.vectorstore.tools.VectorStores");
        } catch (ClassNotFoundException e) {
            return null;
        }
        return create(environment, activations, sessions, embeddings, llmConfigs, workflows);
    }

    /** Separate method so optional types are only linked once the guard passed. */
    private static AttachSupport create(Map<String, String> environment,
                                        DynamicToolActivations activations,
                                        AgentSessionRepository sessions,
                                        LlmEmbeddings embeddings,
                                        LlmConfigRepository llmConfigs,
                                         ai.mindconnect.workflow.persistence.port.WorkflowDataRepository workflows) {
        var fileStore = ai.mindconnect.filestore.FileStoreBackend
                .byType(environment.getOrDefault("fileStoreBackend", "filesystem"))
                .orElseThrow()
                .open(Map.of("dir", environment.getOrDefault("fileStoreDir",
                        environment.get("dataBaseDir") + "/files")));
        var env = new ai.mindconnect.agent.tool.ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) embeddings);
                if (type == LlmConfigRepository.class) return Optional.of((T) llmConfigs);
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
        return new AttachSupport(environment, activations, sessions, fileStore, stores, workflows);
    }

    String attach(UUID sessionId, String fileName, InputStream content) {
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

    /**
     * Ingests an ALREADY-STORED file into the session's vector store and
     * activates {@code vector_search} — the second half of {@link #attach},
     * for callers that upload first (protocol {@code Files.upload}) and
     * reference later ({@code Document(FileId)} content parts).
     */
    String attachStored(UUID sessionId, ai.mindconnect.filestore.StoredFile stored) {
        try {
            String storeName = "session-" + sessionId;
            var template = stores.template("chat-uploads").orElseGet(() -> {
                var created = new ai.mindconnect.vectorstore.tools.VectorStoreTemplate(
                        "chat-uploads", environment.getOrDefault("vectorStoreBackend", "memory"),
                        Map.of(), environment.getOrDefault("vectorStoreEmbeddingConfig", "embeddings"),
                        null, Map.of("description", "Per-chat-session upload stores (auto-created)"));
                stores.registry().saveTemplate(created);
                return created;
            });
            var store = stores.open(storeName, template.name(),
                    ai.mindconnect.vectorstore.tools.VectorStoreInstance.Scope.SESSION,
                    sessionId.toString());
            var instance = stores.settingsFor(storeName);

            String message;
            if (instance.ingestionWorkflow() != null && !instance.ingestionWorkflow().isBlank()
                    && workflowModulesPresent()) {
                message = WorkflowIngestion.run(environment, stores, instance, stored, fileStore, workflows);
            } else {
                String text = new String(fileStore.content(stored.id()).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                message = ai.mindconnect.vectorstore.tools.DirectIngestion.ingest(
                        stores, store, storeName, stored.name(), text);
            }

            activations.activate(sessionId, List.of("vector_search"));
            sessions.findById(sessionId).ifPresent(session ->
                    sessions.save(session.withAttachedFiles(List.of(stored.name()))));
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
                          ai.mindconnect.workflow.persistence.port.WorkflowDataRepository hostWorkflows) throws Exception {
            java.nio.file.Path base = java.nio.file.Path.of(
                    environment.getOrDefault("defaultBaseDir", System.getProperty("user.home")));
            java.nio.file.Path dir = base.resolve("vector-store-uploads").resolve(instance.name());
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(stored.name());
            try (InputStream in = fileStore.content(stored.id())) {
                java.nio.file.Files.copy(in, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            var workflows = hostWorkflows != null ? hostWorkflows
                    : new ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository(
                            java.nio.file.Path.of(environment.get("workflowDir")));
            var workflow = workflows.findById(instance.ingestionWorkflow()).orElseThrow(() ->
                    new IllegalStateException("Ingestion workflow '" + instance.ingestionWorkflow()
                            + "' not found in " + environment.get("workflowDir")));
            var report = new ai.mindconnect.workflow.admin.run.WorkflowRunService(null)
                    .run(workflow, Map.of("file", base.relativize(target).toString(),
                            "store", instance.name()));
            if (!report.success()) {
                throw new IllegalStateException("ingestion workflow failed: " + report.error());
            }
            return stored.name() + " ingested via workflow '" + instance.ingestionWorkflow() + "'.";
        }
    }
}
