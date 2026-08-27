package ai.mindconnect.vectorstore.tools;

import java.util.Map;

/**
 * The policy for a family of vector stores: which backend they live on, which
 * embedding model fills them (fixed here so every store of the template is
 * dimension-consistent), and which workflow ingests documents into them.
 * Concrete stores are just {@link VectorStoreInstance}s — template + name —
 * and may be created on the fly.
 *
 * @param name              unique template name (e.g. {@code knowledge}, {@code chat-uploads})
 * @param backend           backend type ({@code memory}, {@code pgvector})
 * @param backendConfig     backend overrides (dir, url, user, password); empty = host defaults
 * @param embeddingConfig   LlmConfig name for the embedding model
 * @param ingestionWorkflow workflow started by "Ingest file…" (e.g. {@code file-ingestion}); optional
 * @param metadata          free key-values (description, tags, chunking hints)
 */
public record VectorStoreTemplate(
        String name,
        String backend,
        Map<String, String> backendConfig,
        String embeddingConfig,
        String ingestionWorkflow,
        Map<String, String> metadata
) {
    public VectorStoreTemplate {
        if (backendConfig == null) backendConfig = Map.of();
        if (metadata == null) metadata = Map.of();
    }
}
