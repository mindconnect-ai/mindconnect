package ai.mindconnect.vectorstore.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The policy for a family of vector stores: which embedding model fills them
 * (fixed here so every store of the template compares like with like) and
 * which workflow ingests documents into them. Concrete stores are just
 * {@link VectorStoreInstance}s — template + name — and may be created on the
 * fly. Where the vectors live is the template's {@link IndexDefinition index}.
 *
 * @param name              unique template name (e.g. {@code knowledge}, {@code chat-uploads})
 * @param embeddingConfig   LlmConfig name for the embedding model
 * @param ingestionWorkflow workflow started by "Ingest file…" (e.g. {@code file-ingestion}); optional
 * @param metadata          free key-values (description, tags, chunking hints)
 * @param version           the stored version this template was read with — a save with a
 *                          version is refused once another save came in between;
 *                          {@code null} saves without that check
 * @param index             the {@link IndexDefinition} its stores keep their chunks in; {@code null}
 *                          for {@value IndexDefinition#CHAT_UPLOADS} on the chat-uploads template,
 *                          {@value IndexDefinition#DEFAULT} on every other
 */
@JsonIgnoreProperties(ignoreUnknown = true)   // records from before 0.9 still carry backend and backendConfig
public record VectorStoreTemplate(
        String name,
        String embeddingConfig,
        String ingestionWorkflow,
        Map<String, String> metadata,
        Long version,
        String index
) {
    public VectorStoreTemplate {
        if (metadata == null) metadata = Map.of();
    }

    /** Without a version: the template saves without a version check. */
    public VectorStoreTemplate(String name, String embeddingConfig, String ingestionWorkflow, Map<String, String> metadata) {
        this(name, embeddingConfig, ingestionWorkflow, metadata, null, null);
    }

    public VectorStoreTemplate(String name, String embeddingConfig, String ingestionWorkflow, Map<String, String> metadata,
                               Long version) {
        this(name, embeddingConfig, ingestionWorkflow, metadata, version, null);
    }

    /** This template as read with, or to be saved against, {@code version} — nothing else changes. */
    public VectorStoreTemplate withVersion(Long version) {
        return new VectorStoreTemplate(name, embeddingConfig, ingestionWorkflow, metadata, version, index);
    }

    /** This template with its stores in {@code index}. */
    public VectorStoreTemplate withIndex(String index) {
        return new VectorStoreTemplate(name, embeddingConfig, ingestionWorkflow, metadata, version, index);
    }

    /** The index its stores use: as named, else by the rule for templates that name none. */
    public String effectiveIndex() {
        if (index != null && !index.isBlank()) return index;
        return IndexDefinition.CHAT_UPLOADS.equals(name) ? IndexDefinition.CHAT_UPLOADS : IndexDefinition.DEFAULT;
    }
}
