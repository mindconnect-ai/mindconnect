package ai.mindconnect.vectorstore;

import java.util.Map;

/**
 * One embedded piece of a file: the text the agent will read back, where it
 * came from, and its embedding vector. Embeddings are the expensive part —
 * they are computed once at ingestion and persisted with the chunk; no
 * backend ever re-embeds.
 *
 * @param id        unique within the store (e.g. {@code fileId + ":" + ordinal})
 * @param fileId    the source file this chunk belongs to (deletion unit)
 * @param ordinal   position of the chunk within its file
 * @param text      the chunk's text content, returned to the searcher
 * @param metadata  small string map (file name, section title, page, ...)
 * @param embedding the embedding vector; all chunks of a store share one dimension
 */
public record VectorChunk(
        String id,
        String fileId,
        int ordinal,
        String text,
        Map<String, String> metadata,
        float[] embedding
) {
    public VectorChunk {
        if (metadata == null) metadata = Map.of();
    }
}
