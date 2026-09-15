package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.VectorStoreBackend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@link VectorStores} implementation: templates from the host's
 * {@code mindconnect.vector-store.*} properties plus the persisted ones, one
 * {@link FileVectorStoreRegistry} per namespace under
 * {@code <dataBaseDir>/<namespace>/vector-stores}, and the backends found on
 * the classpath. Every call names its namespace; nothing here is bound to one.
 */
public final class DefaultVectorStores implements VectorStores {

    public static final String DEFAULT_TEMPLATE = "default";

    /** The backend config key that names the namespace a store belongs to. */
    public static final String NAMESPACE_KEY = "namespace";

    private final List<VectorStoreBackend> backends;
    private final VectorStoreTemplate defaultTemplate;
    /** One registry per namespace, under {@code <baseDir>/<namespace>/vector-stores}; opened on first use. */
    private final Map<Namespace, FileVectorStoreRegistry> registries = new ConcurrentHashMap<>();
    private final Path baseDir;
    private final LlmEmbeddings embeddings;
    private final LlmConfigRepository configs;
    /** Who says which namespace the config repository answers for; null when it is bound for good. */
    private final ai.mindconnect.agent.ScopeSupplier scope;

    DefaultVectorStores(List<VectorStoreBackend> backends, VectorStoreTemplate defaultTemplate,
                        Path baseDir, LlmEmbeddings embeddings, LlmConfigRepository configs) {
        this(backends, defaultTemplate, baseDir, embeddings, configs, null);
    }

    DefaultVectorStores(List<VectorStoreBackend> backends, VectorStoreTemplate defaultTemplate,
                        Path baseDir, LlmEmbeddings embeddings, LlmConfigRepository configs,
                        ai.mindconnect.agent.ScopeSupplier scope) {
        this.backends = backends;
        this.defaultTemplate = defaultTemplate;
        this.baseDir = baseDir;
        this.embeddings = embeddings;
        this.configs = configs;
        this.scope = scope;
    }

    /** Empty when the environment lacks a backend or the embedding services. */
    static Optional<VectorStores> fromEnvironment(ToolEnvironment env) {
        String type = env.getString("vectorStoreBackend").orElse("memory");
        List<VectorStoreBackend> backends = VectorStoreBackend.discover();
        LlmEmbeddings embeddings = env.get(LlmEmbeddings.class).orElse(null);
        LlmConfigRepository configs = env.get(LlmConfigRepository.class).orElse(null);
        boolean backendKnown = backends.stream().anyMatch(b -> type.equals(b.type()));
        if (!backendKnown || embeddings == null || configs == null) {
            org.slf4j.LoggerFactory.getLogger(VectorStores.class).warn(
                    "Vector tools disabled: backend '{}' {}, LlmEmbeddings {}, LlmConfigRepository {} "
                    + "(discovered backends: {})",
                    type, backendKnown ? "ok" : "not found",
                    embeddings == null ? "missing" : "ok",
                    configs == null ? "missing" : "ok",
                    backends.stream().map(VectorStoreBackend::type).toList());
            return Optional.empty();
        }
        Map<String, String> config = new HashMap<>();
        env.getString("dataBaseDir").ifPresent(v -> config.put("baseDir", v));
        env.getString("vectorStoreUrl").ifPresent(v -> config.put("url", v));
        env.getString("vectorStoreUser").ifPresent(v -> config.put("user", v));
        env.getString("vectorStorePassword").ifPresent(v -> config.put("password", v));
        VectorStoreTemplate defaultTemplate = new VectorStoreTemplate(DEFAULT_TEMPLATE, type, config,
                env.getString("vectorStoreEmbeddingConfig").orElse("embeddings"),
                "file-ingestion",
                Map.of("description", "Built-in template from mindconnect.vector-store.* properties"));
        return Optional.of(new DefaultVectorStores(backends, defaultTemplate,
                Path.of(config.getOrDefault("baseDir", "data")), embeddings, configs,
                env.get(ai.mindconnect.agent.ScopeSupplier.class).orElse(null)));
    }

    // ── templates & instances (registry + built-in default) ───────────────

    /** Forgets {@code namespace}'s registry; its settings directory is gone with the namespace. */
    public void forget(Namespace namespace) {
        registries.remove(namespace);
    }

    @Override
    public FileVectorStoreRegistry registry(Namespace namespace) {
        return registries.computeIfAbsent(namespace,
                ns -> new FileVectorStoreRegistry(baseDir.resolve(ns.value()).resolve("vector-stores")));
    }

    /** The built-in default plus every persisted template. */
    @Override
    public List<VectorStoreTemplate> templates(Namespace namespace) {
        List<VectorStoreTemplate> all = new ArrayList<>();
        all.add(defaultTemplate);
        all.addAll(registry(namespace).templates());
        return all;
    }

    @Override
    public Optional<VectorStoreTemplate> template(Namespace namespace, String name) {
        if (name == null || name.isBlank() || DEFAULT_TEMPLATE.equals(name)) {
            return Optional.of(defaultTemplate);
        }
        return registry(namespace).template(name);
    }

    /**
     * The effective settings for a store name: its registered instance, or a
     * synthetic default-template instance for unregistered names. Instances
     * own their settings — they were copied from the template at creation and
     * may have diverged since.
     */
    @Override
    public VectorStoreInstance settingsFor(Namespace namespace, String storeName) {
        return registry(namespace).instance(storeName).orElseGet(() ->
                VectorStoreInstance.fromTemplate(storeName, defaultTemplate,
                        VectorStoreInstance.Scope.GLOBAL, null));
    }

    /**
     * Opens a store, registering the instance on the fly. For a NEW store the
     * named template's settings are copied onto the instance (with the given
     * scope); an EXISTING instance keeps its own settings — the request's
     * template is ignored, consistency beats convenience.
     */
    @Override
    public VectorStore open(Namespace namespace, String storeName, String templateName,
                            VectorStoreInstance.Scope scope, String scopeRef) {
        return open(namespace, storeName, templateName, scope, scopeRef, null);
    }

    /**
     * Like {@link #open(String, String, VectorStoreInstance.Scope, String)}, for
     * a store that belongs to {@code owner} (a user id). Opened as a chat's
     * store ({@code SESSION} scope), an existing instance without an owner is
     * claimed for that chat — see {@link #claimsChatStore}.
     */
    @Override
    public VectorStore open(Namespace namespace, String storeName, String templateName,
                            VectorStoreInstance.Scope scope, String scopeRef, String owner) {
        FileVectorStoreRegistry registry = registry(namespace);
        VectorStoreInstance instance = registry.instance(storeName).orElse(null);
        if (instance == null) {
            VectorStoreTemplate template = template(namespace, templateName).orElseThrow(() ->
                    new IllegalArgumentException("Unknown vector store template '" + templateName + "'"));
            instance = registry.registerInstance(
                    VectorStoreInstance.fromTemplate(storeName, template, scope, scopeRef, owner));
        } else if (claimsChatStore(instance, storeName, scope, scopeRef, owner)) {
            instance = instance.asChatStore(scopeRef, owner);
            registry.saveInstance(instance);
        }
        return openWith(namespace, instance);
    }

    /**
     * Whether opening a store for its own chat records the chat and its user on
     * an instance that lacks an owner: one registered before owners were
     * recorded, or one registered under the chat's {@code session-} name with
     * another scope (a tool wrote into it before any upload). An instance that
     * has an owner, or that belongs to another session, is left alone.
     */
    private static boolean claimsChatStore(VectorStoreInstance instance, String storeName,
                                           VectorStoreInstance.Scope scope, String scopeRef, String owner) {
        if (owner == null || scopeRef == null || scope != VectorStoreInstance.Scope.SESSION
                || instance.owner() != null) {
            return false;
        }
        return instance.scope() == VectorStoreInstance.Scope.SESSION
                ? scopeRef.equals(instance.scopeRef())
                : storeName.equals(VectorTools.SESSION_STORE_PREFIX + scopeRef);
    }

    /** Opens by the instance's own settings (no registration side effects). */
    @Override
    public VectorStore openWith(Namespace namespace, VectorStoreInstance instance) {
        VectorStoreBackend backend = backends.stream()
                .filter(b -> instance.backend().equals(b.type()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Backend '" + instance.backend() + "' of store '" + instance.name()
                        + "' is not on the classpath"));
        Map<String, String> config = new HashMap<>(defaultTemplate.backendConfig());
        config.putAll(instance.backendConfig());
        config.put(NAMESPACE_KEY, namespace.value());
        return backend.open(instance.name(), config);
    }

    /** Store ids that physically exist on the given backend type. */
    @Override
    public List<String> discoverStores(Namespace namespace, String backendType, Map<String, String> backendConfig) {
        VectorStoreBackend backend = backends.stream()
                .filter(b -> backendType.equals(b.type()))
                .findFirst().orElse(null);
        if (backend == null) {
            return List.of();
        }
        Map<String, String> config = new HashMap<>(defaultTemplate.backendConfig());
        if (backendConfig != null) {
            config.putAll(backendConfig);
        }
        config.put(NAMESPACE_KEY, namespace.value());
        return backend.listStores(config);
    }

    // ── embedding ──────────────────────────────────────────────────────────

    /**
     * Embeds with the instance's own embedding LlmConfig — through an alias to
     * the config behind it: an alias record names no model, URL or key, and
     * a store pointed at {@code embeddings} must follow wherever that name is
     * pointed.
     */
    /**
     * The config as {@code namespace} knows it. The repository is routed by the thread's
     * scope, which is not necessarily the namespace asked for — a caller working across
     * namespaces must not get its own namespace's embedding model for another's store.
     */
    private Optional<LlmConfig> configIn(Namespace namespace, String configName) {
        if (scope instanceof ai.mindconnect.agent.ThreadBoundScope bound) {
            return bound.runIn(ai.mindconnect.agent.Scope.of(namespace), () -> configs.findResolvedByName(configName));
        }
        return configs.findResolvedByName(configName);
    }

    @Override
    public List<float[]> embedFor(Namespace namespace, String storeName, List<String> texts) {
        String configName = settingsFor(namespace, storeName).embeddingConfig();
        LlmConfig config = configIn(namespace, configName)
                .orElseThrow(() -> new IllegalStateException("No LlmConfig named '" + configName
                        + "' (store '" + storeName + "') — create one pointing at an "
                        + "embedding model, e.g. LM Studio's text-embedding-nomic-embed-text-v1.5"));
        return embeddings.embed(config, texts);
    }
}
