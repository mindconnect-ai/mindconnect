package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;

import java.util.List;
import java.util.Optional;

/**
 * Template-aware access to vector stores. A store <em>name</em> resolves via
 * its registered {@link VectorStoreInstance} to its settings — embedding model,
 * ingestion workflow, scope, owner — and instances can be created on the fly
 * (template + name is all it takes).
 *
 * <p>A store is a list of entities; their chunks live once per entity in the
 * namespace's {@link EmbeddingIndex}, which follows the persistence: Postgres
 * with pgvector when the runtime keeps its data there, files under
 * {@code <dataBaseDir>/<namespace>/embeddings} otherwise.
 *
 * <p>Unlike the repositories this port is not routed: every call names the
 * namespace it works in. The host's {@code mindconnect.vector-store.*}
 * properties form the built-in {@code default} template (not persisted,
 * always present), so everything works before anyone defines templates.
 * Unregistered store names resolve to it.
 *
 * <p>ToolEnvironment strings: {@code vectorStoreBackend} ({@code pgvector} or
 * {@code file}; the default follows the persistence), {@code dataBaseDir},
 * {@code vectorStoreUrl} / {@code vectorStoreUser} / {@code vectorStorePassword}
 * (a pgvector database of its own), {@code vectorStoreEmbeddingConfig}
 * (default {@code embeddings}). Under Postgres persistence — the environment
 * offers the runtime's {@code Sql} and {@code DataSource} — the registry is
 * kept in the database.
 */
public interface VectorStores {

    String DEFAULT_TEMPLATE = "default";

    /** Empty when the environment lacks the embedding services. */
    static Optional<VectorStores> fromEnvironment(ToolEnvironment env) {
        return DefaultVectorStores.fromEnvironment(env);
    }

    /** The namespace's registry of templates, instances and members — on files or in Postgres, following the persistence. */
    VectorStoreRegistry registry(Namespace namespace);

    /** The namespace's {@value IndexDefinition#DEFAULT} index. */
    EmbeddingIndex index(Namespace namespace);

    /** The namespace's index {@code name}, as the namespace or the host defines it. */
    EmbeddingIndex index(Namespace namespace, String name);

    /** The indexes the namespace can use: the built-in ones, as the namespace may have redefined them, then its own. */
    List<IndexDefinition> indexes(Namespace namespace);

    /** Whether {@code name} is one of the host's built-in indexes, not redefined by the namespace. */
    boolean isBuiltIn(Namespace namespace, String name);

    /** Saves an index definition of the namespace — a built-in name moves that index. Passwords are stored encrypted. */
    void saveIndex(Namespace namespace, IndexDefinition index);

    /** Deletes the namespace's definition; a built-in name then falls back to the host's. */
    void deleteIndex(Namespace namespace, String name);

    /** The name of the index a store keeps its chunks in. */
    String indexOf(Namespace namespace, VectorStoreInstance instance);

    /** Where index {@code name} lives, for people — and why on files when it is. */
    default String indexLocation(Namespace namespace, String name) {
        return "unknown";
    }

    /**
     * Where the namespace's embedding index lives, for people: "Postgres
     * (pgvector) …" or "Files in …", and why when it is not where one might
     * expect it.
     */
    default String indexLocation(Namespace namespace) {
        return "unknown";
    }

    /** The built-in default plus every template persisted in the namespace. */
    List<VectorStoreTemplate> templates(Namespace namespace);

    Optional<VectorStoreTemplate> template(Namespace namespace, String name);

    /**
     * The effective settings for a store name: its registered instance, or a
     * synthetic default-template instance for unregistered names. Instances
     * own their settings — they were copied from the template at creation and
     * may have diverged since.
     */
    VectorStoreInstance settingsFor(Namespace namespace, String storeName);

    /**
     * Opens a store, registering the instance on the fly. For a NEW store the
     * named template's settings are copied onto the instance (with the given
     * scope); an EXISTING instance keeps its own settings — the request's
     * template is ignored, consistency beats convenience.
     */
    VectorStore open(Namespace namespace, String storeName, String templateName,
                     VectorStoreInstance.Scope scope, String scopeRef);

    /**
     * Like {@link #open(Namespace, String, String, VectorStoreInstance.Scope, String)}, for
     * a store that belongs to {@code owner} (a user id). Opened as a chat's
     * store ({@code SESSION} scope), an existing instance without an owner is
     * claimed for that chat.
     */
    VectorStore open(Namespace namespace, String storeName, String templateName,
                     VectorStoreInstance.Scope scope, String scopeRef, String owner);

    /** The store by its settings as they are — registered or the default's — without registering anything. */
    VectorStore store(Namespace namespace, String storeName);

    /**
     * The key the index keeps a store's vectors under: provider and model of
     * the embedding config the store names, followed through aliases — two
     * stores on the same model share their entities' vectors.
     */
    String embeddingModel(Namespace namespace, VectorStoreInstance instance);

    /**
     * Embeds with the store's own embedding LlmConfig — through an alias to
     * the config behind it: an alias record names no model, URL or key, and
     * a store pointed at {@code embeddings} must follow wherever that name is
     * pointed.
     */
    List<float[]> embedFor(Namespace namespace, String storeName, List<String> texts);
}
