package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;
import ai.mindconnect.vectorstore.embedding.FileEntryStore;
import ai.mindconnect.vectorstore.pgvector.PgEmbeddingIndex;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The one {@link VectorStores} implementation: templates from the host's
 * {@code mindconnect.vector-store.*} properties plus the persisted ones, and
 * per namespace one {@link VectorStoreRegistry} and one {@link EmbeddingIndex}
 * per {@link IndexDefinition index}. Every call names its namespace; nothing
 * here is bound to one.
 *
 * <p>The registry follows the runtime's persistence: files under
 * {@code <dataBaseDir>/<namespace>/vector-stores}, or Postgres
 * ({@link PostgresVectorStores}). The indexes follow their definitions. Two are
 * built in from the host's settings — {@value IndexDefinition#DEFAULT} and
 * {@value IndexDefinition#CHAT_UPLOADS}, in the table {@value #CHAT_TABLE} —
 * in the application's database when it keeps its data in Postgres with
 * pgvector, in a database of their own when {@code vectorStoreUrl} names one,
 * in files otherwise. A namespace may save definitions of its own: under a
 * built-in name to move it, under a new name for templates to use. A pgvector
 * definition whose database has no pgvector falls back to files, and says so.
 *
 * <p>When a namespace is first opened, the stores it kept before 0.9 — one
 * JSONL file or one {@code vs_*} table per store — are read into their
 * indexes once ({@link LegacyVectorStores}).
 */
public final class DefaultVectorStores implements VectorStores {

    private static final Logger log = LoggerFactory.getLogger(DefaultVectorStores.class);

    public static final String DEFAULT_TEMPLATE = "default";

    /** The built-in chat-uploads index's table, apart from everything else. */
    public static final String CHAT_TABLE = "mc_embedding_chat";

    private static final String FILE = IndexDefinition.FILE;

    /** One DataSource per url+user for indexes in a database of their own — stateless, sharing is safe. */
    private static final Map<String, DataSource> DATA_SOURCES = new ConcurrentHashMap<>();

    private final VectorStoreTemplate defaultTemplate;
    private final Map<Namespace, VectorStoreRegistry> registries = new ConcurrentHashMap<>();
    private final Function<Namespace, VectorStoreRegistry> openRegistry;
    /** The indexes opened per namespace, by name, with the definition they were opened from. */
    private final Map<Namespace, Map<String, Opened>> opened = new ConcurrentHashMap<>();
    /** The namespaces whose stores from before 0.9 have been looked at in this process. */
    private final Set<Namespace> imported = ConcurrentHashMap.newKeySet();
    /** Reads what a namespace kept before 0.9; null when there is nothing to look for. */
    private final LegacyVectorStores legacy;
    private final Host host;
    private final LlmEmbeddings embeddings;
    private final LlmConfigRepository configs;
    /** Who says which namespace the config repository answers for; null when it is bound for good. */
    private final ai.mindconnect.agent.ScopeSupplier scope;

    /**
     * What the host brings: its data directory, the application's database (null
     * on files), the built-in index definitions, why they are on files when they
     * are, and how passwords of definitions are decrypted.
     */
    record Host(Path baseDir, Sql runtimeSql, DataSource runtimeDb, Map<String, IndexDefinition> builtIn,
                String fileReason, EncryptionHelper encryption) {}

    private record Opened(IndexDefinition definition, EmbeddingIndex index, String location) {}

    /** Registry and indexes on files, under {@code <baseDir>/<namespace>/}. */
    DefaultVectorStores(VectorStoreTemplate defaultTemplate, Path baseDir, LlmEmbeddings embeddings,
                        LlmConfigRepository configs, ai.mindconnect.agent.ScopeSupplier scope) {
        this(defaultTemplate, fileRegistries(baseDir), new LegacyVectorStores(baseDir, null),
                new Host(baseDir, null, null, builtIn(FILE, null, null, null), "file persistence", null),
                embeddings, configs, scope);
    }

    DefaultVectorStores(VectorStoreTemplate defaultTemplate, Function<Namespace, VectorStoreRegistry> openRegistry,
                        LegacyVectorStores legacy, Host host, LlmEmbeddings embeddings, LlmConfigRepository configs,
                        ai.mindconnect.agent.ScopeSupplier scope) {
        this.defaultTemplate = defaultTemplate;
        this.openRegistry = openRegistry;
        this.legacy = legacy;
        this.host = host;
        this.embeddings = embeddings;
        this.configs = configs;
        this.scope = scope;
    }

    /**
     * Empty when the environment lacks the embedding services.
     *
     * <p>A runtime that keeps its data in Postgres registers its {@link Sql} and
     * {@link DataSource}: the registry then lives in that database, and so do the
     * built-in indexes — when it has pgvector, and unless {@code vectorStoreBackend}
     * says {@code file} or {@code vectorStoreUrl} names another database.
     */
    static Optional<VectorStores> fromEnvironment(ToolEnvironment env) {
        LlmEmbeddings embeddings = env.get(LlmEmbeddings.class).orElse(null);
        LlmConfigRepository configs = env.get(LlmConfigRepository.class).orElse(null);
        if (embeddings == null || configs == null) {
            log.warn("Vector tools disabled: LlmEmbeddings {}, LlmConfigRepository {}",
                    embeddings == null ? "missing" : "ok", configs == null ? "missing" : "ok");
            return Optional.empty();
        }
        Sql sql = env.get(Sql.class).orElse(null);
        DataSource runtimeDb = sql == null ? null : env.get(DataSource.class).orElse(null);
        Path baseDir = Path.of(env.getString("dataBaseDir").orElse("data"));
        VectorStoreTemplate defaultTemplate = new VectorStoreTemplate(DEFAULT_TEMPLATE,
                env.getString("vectorStoreEmbeddingConfig").orElse("embeddings"),
                "file-ingestion",
                Map.of("description", "Built-in template from mindconnect.vector-store.* properties"));

        String backend = env.getString("vectorStoreBackend").orElse("");
        Optional<String> url = env.getString("vectorStoreUrl");
        Map<String, IndexDefinition> builtIn;
        String fileReason;
        if (FILE.equals(backend) || "memory".equals(backend)) {
            builtIn = builtIn(FILE, null, null, null);
            fileReason = "mindconnect.vector-store.backend is '" + backend + "'";
        } else if (url.isPresent()) {
            builtIn = builtIn(IndexDefinition.PGVECTOR, url.get(), env.getString("vectorStoreUser").orElse(null),
                    env.getString("vectorStorePassword").orElse(null));
            fileReason = null;
        } else if (runtimeDb != null) {
            builtIn = builtIn(IndexDefinition.PGVECTOR, null, null, null);
            fileReason = null;
        } else {
            builtIn = builtIn(FILE, null, null, null);
            fileReason = "file persistence";
        }
        Function<Namespace, VectorStoreRegistry> registries = sql == null
                ? fileRegistries(baseDir)
                : new PostgresVectorStores(sql, baseDir)::open;
        Host host = new Host(baseDir, sql, runtimeDb, builtIn, fileReason,
                env.get(EncryptionHelper.class).orElse(null));
        return Optional.of(new DefaultVectorStores(defaultTemplate, registries,
                new LegacyVectorStores(baseDir, runtimeDb), host, embeddings, configs,
                env.get(ai.mindconnect.agent.ScopeSupplier.class).orElse(null)));
    }

    /** The two built-in definitions of the given kind: the default table, and chat uploads apart from it. */
    private static Map<String, IndexDefinition> builtIn(String kind, String url, String user, String password) {
        Map<String, IndexDefinition> builtIn = new LinkedHashMap<>();
        builtIn.put(IndexDefinition.DEFAULT, new IndexDefinition(IndexDefinition.DEFAULT, kind, null, url, user,
                password, null, "Built-in: everything that names no index of its own"));
        builtIn.put(IndexDefinition.CHAT_UPLOADS, new IndexDefinition(IndexDefinition.CHAT_UPLOADS, kind,
                CHAT_TABLE, url, user, password, null, "Built-in: the files attached to chats, apart from the rest"));
        return Map.copyOf(builtIn);
    }

    private static Function<Namespace, VectorStoreRegistry> fileRegistries(Path baseDir) {
        return ns -> new FileVectorStoreRegistry(baseDir.resolve(ns.value()).resolve("vector-stores"));
    }

    // ── registry & indexes per namespace ───────────────────────────────────

    /** Forgets {@code namespace}'s registry and indexes; its files and rows are gone with the namespace. */
    public void forget(Namespace namespace) {
        registries.remove(namespace);
        imported.remove(namespace);
        Map<String, Opened> gone = opened.remove(namespace);
        if (gone != null) {
            gone.values().stream().filter(o -> !o.definition().pgvector() || o.location().startsWith("Files"))
                    .forEach(o -> FileEntryStore.forget(fileDirectory(namespace, o.definition())));
        }
    }

    /**
     * Removes what the namespace kept in indexes of a database of their own — the
     * namespace purge clears the application's database by itself — then
     * {@link #forget forgets} it.
     */
    public void purge(Namespace namespace) {
        for (IndexDefinition definition : indexes(namespace)) {
            if (!definition.pgvector() || definition.url() == null) continue;
            try {
                Sql.of(dataSource(definition)).update("DELETE FROM " + definition.effectiveTable()
                        + " WHERE namespace = ?", namespace.value());
            } catch (RuntimeException e) {
                log.warn("Index '{}' of namespace '{}' could not be cleared: {}", definition.name(),
                        namespace.value(), e.getMessage());
            }
        }
        forget(namespace);
    }

    @Override
    public VectorStoreRegistry registry(Namespace namespace) {
        return registries.computeIfAbsent(namespace, openRegistry);
    }

    @Override
    public EmbeddingIndex index(Namespace namespace) {
        return index(namespace, IndexDefinition.DEFAULT);
    }

    @Override
    public EmbeddingIndex index(Namespace namespace, String name) {
        EmbeddingIndex index = open(namespace, name).index();
        importOnce(namespace);
        return index;
    }

    @Override
    public List<IndexDefinition> indexes(Namespace namespace) {
        Map<String, IndexDefinition> all = new LinkedHashMap<>();
        all.put(IndexDefinition.DEFAULT, host.builtIn().get(IndexDefinition.DEFAULT));
        all.put(IndexDefinition.CHAT_UPLOADS, host.builtIn().get(IndexDefinition.CHAT_UPLOADS));
        registry(namespace).indexes().forEach(i -> all.put(i.name(), i));
        return List.copyOf(all.values());
    }

    @Override
    public boolean isBuiltIn(Namespace namespace, String name) {
        return host.builtIn().containsKey(name) && registry(namespace).index(name).isEmpty();
    }

    @Override
    public void saveIndex(Namespace namespace, IndexDefinition index) {
        String password = index.password();
        // Kept encrypted like an LLM config's key; a ${VAR} placeholder stays as it is.
        if (password != null && host.encryption() != null && !password.startsWith("enc:")
                && !ai.mindconnect.common.env.EnvVarResolver.containsPlaceholder(password)) {
            index = index.withPassword(host.encryption().encryptTagged(password));
        }
        registry(namespace).saveIndex(index);
        Map<String, Opened> byName = opened.get(namespace);
        if (byName != null) byName.remove(index.name());
    }

    @Override
    public void deleteIndex(Namespace namespace, String name) {
        registry(namespace).deleteIndex(name);
        Map<String, Opened> byName = opened.get(namespace);
        if (byName != null) byName.remove(name);
    }

    @Override
    public String indexLocation(Namespace namespace) {
        return indexLocation(namespace, IndexDefinition.DEFAULT);
    }

    @Override
    public String indexLocation(Namespace namespace, String name) {
        return open(namespace, name).location();
    }

    @Override
    public String indexOf(Namespace namespace, VectorStoreInstance instance) {
        if (instance.index() != null && !instance.index().isBlank()) {
            return instance.index();
        }
        // From before indexes: the template's rule, by the template's name.
        return template(namespace, instance.templateName()).map(VectorStoreTemplate::effectiveIndex)
                .orElseGet(() -> new VectorStoreTemplate(instance.templateName() == null ? "" : instance.templateName(),
                        null, null, null).effectiveIndex());
    }

    private IndexDefinition definition(Namespace namespace, String name) {
        return registry(namespace).index(name)
                .or(() -> Optional.ofNullable(host.builtIn().get(name)))
                .orElseThrow(() -> new IllegalArgumentException("There is no index '" + name + "'"));
    }

    /** The index {@code name} of the namespace, opened again when its definition changed. */
    private Opened open(Namespace namespace, String name) {
        IndexDefinition definition = definition(namespace, name);
        Map<String, Opened> byName = opened.computeIfAbsent(namespace, ns -> new ConcurrentHashMap<>());
        Opened known = byName.get(name);
        if (known != null && known.definition().equals(definition)) {
            return known;
        }
        Opened fresh = openIndex(namespace, definition);
        byName.put(name, fresh);
        return fresh;
    }

    private Opened openIndex(Namespace namespace, IndexDefinition definition) {
        String fileReason = host.fileReason();
        if (definition.pgvector()) {
            DataSource db = definition.url() == null ? host.runtimeDb() : dataSource(definition);
            if (db == null) {
                fileReason = "no database for pgvector (file persistence)";
            } else if (!PostgresVectorStores.pgvectorAvailable(db)) {
                fileReason = definition.url() == null ? "the application's Postgres has no pgvector extension"
                        : "the database has no pgvector extension";
            } else {
                Sql sql = definition.url() == null && host.runtimeSql() != null ? host.runtimeSql() : Sql.of(db);
                String where = definition.url() == null ? "the application's database"
                        : withoutCredentials(definition.url());
                return new Opened(definition, new PgEmbeddingIndex(sql, namespace, definition.effectiveTable()),
                        "Postgres (pgvector), " + where + " — table " + definition.effectiveTable());
            }
        }
        Path dir = fileDirectory(namespace, definition);
        return new Opened(definition, FileEntryStore.index(dir),
                "Files in " + dir + (fileReason == null ? "" : " — " + fileReason));
    }

    private Path fileDirectory(Namespace namespace, IndexDefinition definition) {
        return host.baseDir().resolve(namespace.value()).resolve(definition.effectiveDirectory());
    }

    private DataSource dataSource(IndexDefinition definition) {
        String password = definition.password();
        if (password != null && host.encryption() != null) {
            password = host.encryption().resolve(password);
        }
        if (password != null) {
            password = ai.mindconnect.common.env.EnvVarResolver.system().resolve(password);
        }
        String resolved = password;
        return DATA_SOURCES.computeIfAbsent(definition.url() + "|" + definition.user() + "|" + (resolved == null ? 0 : resolved.hashCode()),
                key -> {
                    PGSimpleDataSource ds = new PGSimpleDataSource();
                    ds.setUrl(definition.url());
                    if (definition.user() != null) ds.setUser(definition.user());
                    if (resolved != null) ds.setPassword(resolved);
                    return ds;
                });
    }

    /** A JDBC URL as it may be shown: without user or password parameters. */
    private static String withoutCredentials(String url) {
        return url.replaceAll("(?i)([?&])(user|password)=[^&]*&?", "$1").replaceAll("[?&]$", "");
    }

    /** Reads what the namespace kept before 0.9 into the indexes its stores name, once per process. */
    private void importOnce(Namespace namespace) {
        if (legacy == null || !imported.add(namespace)) {
            return;
        }
        try {
            legacy.importInto(namespace, registry(namespace),
                    instance -> open(namespace, indexOf(namespace, instance)).index(),
                    defaultTemplate, instance -> embeddingModel(namespace, instance));
        } catch (RuntimeException e) {
            // The new stores work regardless; the next start tries again.
            log.warn("Vector stores of namespace '{}' from before 0.9 could not all be imported: {}",
                    namespace.value(), e.getMessage(), e);
        }
    }

    // ── templates & instances (registry + built-in default) ───────────────

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

    @Override
    public VectorStoreInstance settingsFor(Namespace namespace, String storeName) {
        return registry(namespace).instance(storeName).orElseGet(() ->
                VectorStoreInstance.fromTemplate(storeName, defaultTemplate,
                        VectorStoreInstance.Scope.GLOBAL, null));
    }

    @Override
    public VectorStore open(Namespace namespace, String storeName, String templateName,
                            VectorStoreInstance.Scope scope, String scopeRef) {
        return open(namespace, storeName, templateName, scope, scopeRef, null);
    }

    @Override
    public VectorStore open(Namespace namespace, String storeName, String templateName,
                            VectorStoreInstance.Scope scope, String scopeRef, String owner) {
        VectorStoreRegistry registry = registry(namespace);
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
        return handle(namespace, instance);
    }

    @Override
    public VectorStore store(Namespace namespace, String storeName) {
        return handle(namespace, settingsFor(namespace, storeName));
    }

    private VectorStore handle(Namespace namespace, VectorStoreInstance instance) {
        return new VectorStore(instance, registry(namespace), index(namespace, indexOf(namespace, instance)),
                () -> embeddingModel(namespace, instance), texts -> embed(namespace, instance, texts));
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

    // ── embedding ──────────────────────────────────────────────────────────

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

    private LlmConfig config(Namespace namespace, VectorStoreInstance instance) {
        String configName = instance.embeddingConfig();
        return configIn(namespace, configName)
                .orElseThrow(() -> new IllegalStateException("No LlmConfig named '" + configName
                        + "' (store '" + instance.name() + "') — create one pointing at an "
                        + "embedding model, e.g. LM Studio's text-embedding-nomic-embed-text-v1.5"));
    }

    @Override
    public String embeddingModel(Namespace namespace, VectorStoreInstance instance) {
        LlmConfig config = config(namespace, instance);
        return config.provider() + ":" + resolved(config.model());
    }

    /**
     * The model as the gateway calls it: a config may name it as
     * {@code ${EMBEDDING_MODEL:…}}, and the key must follow the variable — else
     * a store would keep comparing against vectors of the model it named before.
     */
    private static String resolved(String model) {
        try {
            return ai.mindconnect.common.env.EnvVarResolver.system().resolve(model);
        } catch (IllegalStateException e) {
            return model;
        }
    }

    private List<float[]> embed(Namespace namespace, VectorStoreInstance instance, List<String> texts) {
        return embeddings.embed(config(namespace, instance), texts);
    }

    @Override
    public List<float[]> embedFor(Namespace namespace, String storeName, List<String> texts) {
        return embed(namespace, settingsFor(namespace, storeName), texts);
    }
}
