package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.vectorstore.VectorStore;

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
 * <p>A store lives in a namespace, and unlike the repositories this port is
 * not routed: every call names the namespace it works in. The registry, the
 * memory backend's files and the pgvector tables are all per namespace; the
 * templates from the host's properties are shared.
 *
 * <p>The host's {@code mindconnect.vector-store.*} properties form the
 * built-in {@code default} template (not persisted, always present), so
 * everything works before anyone defines templates. Unregistered store names
 * resolve to it.
 *
 * <p>ToolEnvironment strings: {@code vectorStoreBackend} (default
 * {@code memory}; under Postgres persistence {@code pgvector} when the
 * database has it), {@code dataBaseDir} (the {@code memory} backend and, on
 * files, the registry live in {@code <dataBaseDir>/<namespace>/vector-stores}),
 * {@code vectorStoreUrl} / {@code vectorStoreUser} / {@code vectorStorePassword},
 * {@code vectorStoreEmbeddingConfig} (default {@code embeddings}). Under
 * Postgres persistence — the environment offers the runtime's {@code Sql} and
 * {@code DataSource} — the registry is kept in the database.
 */
public interface VectorStores {

    String DEFAULT_TEMPLATE = "default";
    /** The backend config key that names the namespace a store belongs to. */
    String NAMESPACE_KEY = "namespace";

    /** Empty when the environment lacks a backend or the embedding services. */
    static Optional<VectorStores> fromEnvironment(ToolEnvironment env) {
        return DefaultVectorStores.fromEnvironment(env);
    }

    /** The namespace's registry of templates and instances — on files or in Postgres, following the persistence. */
    VectorStoreRegistry registry(Namespace namespace);

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

    /** Opens by the instance's own settings (no registration side effects). */
    VectorStore openWith(Namespace namespace, VectorStoreInstance instance);

    /** Store ids that physically exist on the given backend type, in the namespace. */
    List<String> discoverStores(Namespace namespace, String backendType, Map<String, String> backendConfig);

    /**
     * Embeds with the instance's own embedding LlmConfig — through an alias to
     * the config behind it: an alias record names no model, URL or key, and
     * a store pointed at {@code embeddings} must follow wherever that name is
     * pointed.
     */
    List<float[]> embedFor(Namespace namespace, String storeName, List<String> texts);
}
