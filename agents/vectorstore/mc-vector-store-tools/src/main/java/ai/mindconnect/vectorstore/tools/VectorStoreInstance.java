package ai.mindconnect.vectorstore.tools;

import java.time.Instant;
import java.util.Map;

/**
 * One concrete vector store. Created from a {@link VectorStoreTemplate} —
 * the template's settings are <em>copied</em> here at creation time, so the
 * instance owns its configuration and may diverge from the template later;
 * editing a template never silently changes (or breaks) existing stores.
 * {@code templateName} is provenance, not a live link.
 *
 * <p>{@code scope} ties the store to a lifecycle and visibility: a chat
 * session's upload store ({@code SESSION} + session id), an agent's knowledge
 * base ({@code AGENT} + agent name), or a {@code GLOBAL} store.
 */
public record VectorStoreInstance(
        String name,
        String templateName,
        String backend,
        Map<String, String> backendConfig,
        String embeddingConfig,
        String ingestionWorkflow,
        Map<String, String> metadata,
        Scope scope,
        String scopeRef,
        Instant createdAt
) {
    public enum Scope { GLOBAL, AGENT, SESSION }

    public VectorStoreInstance {
        if (backendConfig == null) backendConfig = Map.of();
        if (metadata == null) metadata = Map.of();
        if (scope == null) scope = Scope.GLOBAL;
    }

    /** Creation: copy the template's settings onto the new instance. */
    public static VectorStoreInstance fromTemplate(String name, VectorStoreTemplate template,
                                                   Scope scope, String scopeRef) {
        return new VectorStoreInstance(name, template.name(), template.backend(),
                template.backendConfig(), template.embeddingConfig(), template.ingestionWorkflow(),
                template.metadata(), scope, scopeRef, Instant.now());
    }
}
