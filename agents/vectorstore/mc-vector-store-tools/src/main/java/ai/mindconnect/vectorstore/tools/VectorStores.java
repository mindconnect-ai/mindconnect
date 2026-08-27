package ai.mindconnect.vectorstore.tools;

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

/**
 * Template-aware access to vector stores. A store <em>name</em> resolves via
 * its registered {@link VectorStoreInstance} to a {@link VectorStoreTemplate},
 * which dictates backend + embedding model — so every store of a template is
 * dimension-consistent, and instances can be created on the fly (template +
 * name is all it takes).
 *
 * <p>The host's {@code mindconnect.vector-store.*} properties form the
 * built-in {@code default} template (not persisted, always present), so
 * everything works before anyone defines templates. Unregistered store names
 * resolve to it.
 *
 * <p>ToolEnvironment strings: {@code vectorStoreBackend} (default
 * {@code memory}), {@code vectorStoreDir} / {@code vectorStoreUrl} /
 * {@code vectorStoreUser} / {@code vectorStorePassword},
 * {@code vectorStoreEmbeddingConfig} (default {@code embeddings}).
 */
public final class VectorStores {

    public static final String DEFAULT_TEMPLATE = "default";

    private final List<VectorStoreBackend> backends;
    private final VectorStoreTemplate defaultTemplate;
    private final FileVectorStoreRegistry registry;
    private final LlmEmbeddings embeddings;
    private final LlmConfigRepository configs;

    VectorStores(List<VectorStoreBackend> backends, VectorStoreTemplate defaultTemplate,
                 FileVectorStoreRegistry registry, LlmEmbeddings embeddings, LlmConfigRepository configs) {
        this.backends = backends;
        this.defaultTemplate = defaultTemplate;
        this.registry = registry;
        this.embeddings = embeddings;
        this.configs = configs;
    }

    /** Empty when the environment lacks a backend or the embedding services. */
    public static Optional<VectorStores> fromEnvironment(ToolEnvironment env) {
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
        env.getString("vectorStoreDir").ifPresent(v -> config.put("dir", v));
        env.getString("vectorStoreUrl").ifPresent(v -> config.put("url", v));
        env.getString("vectorStoreUser").ifPresent(v -> config.put("user", v));
        env.getString("vectorStorePassword").ifPresent(v -> config.put("password", v));
        VectorStoreTemplate defaultTemplate = new VectorStoreTemplate(DEFAULT_TEMPLATE, type, config,
                env.getString("vectorStoreEmbeddingConfig").orElse("embeddings"),
                "file-ingestion",
                Map.of("description", "Built-in template from mindconnect.vector-store.* properties"));
        Path root = Path.of(config.getOrDefault("dir", "data/vector-stores"));
        return Optional.of(new VectorStores(backends, defaultTemplate,
                new FileVectorStoreRegistry(root), embeddings, configs));
    }

    // ── templates & instances (registry + built-in default) ───────────────

    public FileVectorStoreRegistry registry() {
        return registry;
    }

    /** The built-in default plus every persisted template. */
    public List<VectorStoreTemplate> templates() {
        List<VectorStoreTemplate> all = new ArrayList<>();
        all.add(defaultTemplate);
        all.addAll(registry.templates());
        return all;
    }

    public Optional<VectorStoreTemplate> template(String name) {
        if (name == null || name.isBlank() || DEFAULT_TEMPLATE.equals(name)) {
            return Optional.of(defaultTemplate);
        }
        return registry.template(name);
    }

    /**
     * The effective settings for a store name: its registered instance, or a
     * synthetic default-template instance for unregistered names. Instances
     * own their settings — they were copied from the template at creation and
     * may have diverged since.
     */
    public VectorStoreInstance settingsFor(String storeName) {
        return registry.instance(storeName).orElseGet(() ->
                VectorStoreInstance.fromTemplate(storeName, defaultTemplate,
                        VectorStoreInstance.Scope.GLOBAL, null));
    }

    /**
     * Opens a store, registering the instance on the fly. For a NEW store the
     * named template's settings are copied onto the instance (with the given
     * scope); an EXISTING instance keeps its own settings — the request's
     * template is ignored, consistency beats convenience.
     */
    public VectorStore open(String storeName, String templateName,
                            VectorStoreInstance.Scope scope, String scopeRef) {
        VectorStoreInstance instance = registry.instance(storeName).orElse(null);
        if (instance == null) {
            VectorStoreTemplate template = template(templateName).orElseThrow(() ->
                    new IllegalArgumentException("Unknown vector store template '" + templateName + "'"));
            instance = registry.registerInstance(
                    VectorStoreInstance.fromTemplate(storeName, template, scope, scopeRef));
        }
        return openWith(instance);
    }

    /** Opens by the instance's own settings (no registration side effects). */
    public VectorStore openWith(VectorStoreInstance instance) {
        VectorStoreBackend backend = backends.stream()
                .filter(b -> instance.backend().equals(b.type()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Backend '" + instance.backend() + "' of store '" + instance.name()
                        + "' is not on the classpath"));
        Map<String, String> config = new HashMap<>(defaultTemplate.backendConfig());
        config.putAll(instance.backendConfig());
        return backend.open(instance.name(), config);
    }

    /** Store ids that physically exist on the given backend type. */
    public List<String> discoverStores(String backendType, Map<String, String> backendConfig) {
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
        return backend.listStores(config);
    }

    // ── embedding ──────────────────────────────────────────────────────────

    /** Embeds with the instance's own embedding LlmConfig. */
    public List<float[]> embedFor(String storeName, List<String> texts) {
        String configName = settingsFor(storeName).embeddingConfig();
        LlmConfig config = configs.findByName(configName)
                .orElseThrow(() -> new IllegalStateException("No LlmConfig named '" + configName
                        + "' (store '" + storeName + "') — create one pointing at an "
                        + "embedding model, e.g. LM Studio's text-embedding-nomic-embed-text-v1.5"));
        return embeddings.embed(config, texts);
    }
}
