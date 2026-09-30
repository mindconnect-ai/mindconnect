package ai.mindconnect.vectorstore.embedding;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * One embedded piece of an entity's text.
 *
 * @param id        unique within the entity and model (e.g. {@code "c" + ordinal})
 * @param ordinal   position of the chunk within the entity's text
 * @param text      the text returned to the searcher
 * @param metadata  small string map a search can filter on (sender, page, due date, ...);
 *                  entries without a value are dropped
 * @param embedding the vector; all chunks of one entity and model share one dimension
 */
public record EmbeddingChunk(
        String id,
        int ordinal,
        String text,
        Map<String, String> metadata,
        float[] embedding
) {

    public EmbeddingChunk {
        metadata = metadata == null ? Map.of() : metadata.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        embedding = embedding == null ? new float[0] : embedding;
    }

    /** The same chunk with another vector — or none, as search hits carry it. */
    public EmbeddingChunk withEmbedding(float[] embedding) {
        return new EmbeddingChunk(id, ordinal, text, metadata, embedding);
    }
}
