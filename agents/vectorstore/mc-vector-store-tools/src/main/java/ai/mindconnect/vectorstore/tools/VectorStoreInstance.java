package ai.mindconnect.vectorstore.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * One concrete vector store: a named list of entities — files, documents,
 * mail — whose embedded chunks a search looks through. Created from a
 * {@link VectorStoreTemplate}; the template's settings are <em>copied</em>
 * here at creation time, so the instance owns its configuration and may
 * diverge from the template later. {@code templateName} is provenance, not a
 * live link.
 *
 * <p>The chunks are not the store's: they live once per entity in the
 * namespace's {@link ai.mindconnect.vectorstore.embedding.EmbeddingIndex}, and
 * a file listed by three stores is embedded once. The store only keeps which
 * entities it lists ({@link VectorStoreRegistry#members}).
 *
 * <p>{@code scope} ties the store to a lifecycle and visibility: a chat
 * session's upload store ({@code SESSION} + session id), an agent's knowledge
 * base ({@code AGENT} + agent name), or a {@code GLOBAL} store.
 *
 * <p>{@code index} names the {@link IndexDefinition} its chunks are kept in —
 * copied from the template; {@code null} on stores from before indexes, which
 * then follow {@link VectorStoreTemplate#effectiveIndex the template's rule}
 * by their template's name.
 *
 * <p>{@code owner} is the user a store's content belongs to — for a chat's
 * upload store, the user of that chat. Null for shared stores, and for stores
 * registered before owners were recorded.
 */
@JsonIgnoreProperties(ignoreUnknown = true)   // records from before 0.9 still carry backend and backendConfig
public record VectorStoreInstance(
        String name,
        String templateName,
        String embeddingConfig,
        String ingestionWorkflow,
        Map<String, String> metadata,
        Scope scope,
        String scopeRef,
        String owner,
        Instant createdAt,
        String index
) {
    public enum Scope { GLOBAL, AGENT, SESSION }

    public VectorStoreInstance {
        if (metadata == null) metadata = Map.of();
        if (scope == null) scope = Scope.GLOBAL;
    }

    /** An instance whose index follows its template. */
    public VectorStoreInstance(String name, String templateName, String embeddingConfig, String ingestionWorkflow,
                               Map<String, String> metadata, Scope scope, String scopeRef, String owner,
                               Instant createdAt) {
        this(name, templateName, embeddingConfig, ingestionWorkflow, metadata, scope, scopeRef, owner, createdAt, null);
    }

    /** Creation: copy the template's settings onto the new instance. */
    public static VectorStoreInstance fromTemplate(String name, VectorStoreTemplate template,
                                                   Scope scope, String scopeRef) {
        return fromTemplate(name, template, scope, scopeRef, null);
    }

    /** Creation of a store that belongs to {@code owner} (a user id). */
    public static VectorStoreInstance fromTemplate(String name, VectorStoreTemplate template,
                                                   Scope scope, String scopeRef, String owner) {
        return new VectorStoreInstance(name, template.name(), template.embeddingConfig(),
                template.ingestionWorkflow(), template.metadata(), scope, scopeRef, owner, Instant.now(),
                template.effectiveIndex());
    }

    /** This instance as the upload store of chat {@code scopeRef}, belonging to {@code owner}. */
    public VectorStoreInstance asChatStore(String scopeRef, String owner) {
        return new VectorStoreInstance(name, templateName, embeddingConfig, ingestionWorkflow, metadata,
                Scope.SESSION, scopeRef, owner, createdAt, index);
    }
}
