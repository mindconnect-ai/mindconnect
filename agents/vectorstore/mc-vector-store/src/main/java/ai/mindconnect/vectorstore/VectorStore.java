package ai.mindconnect.vectorstore;

import java.util.List;

/**
 * One named vector store: embedded chunks in, similarity search out. Obtained
 * from a {@link VectorStoreBackend}; a store id maps to a chat session, an
 * agent knowledge base, or any other corpus the host defines.
 *
 * <p>Implementations own their persistence and their memory strategy — the
 * built-in memory backend keeps loaded stores on the heap and evicts idle
 * ones, pgvector keeps everything in Postgres.
 */
public interface VectorStore {

    /** The store's id, as passed to {@link VectorStoreBackend#open}. */
    String id();

    /**
     * Inserts or replaces chunks by {@link VectorChunk#id()}. All chunks of a
     * store must share one embedding dimension; implementations reject
     * mismatches with {@link IllegalArgumentException}.
     */
    void upsert(List<VectorChunk> chunks);

    /**
     * The {@code topK} most similar chunks by cosine similarity, best first.
     * Scores are in {@code [-1, 1]} (1 = identical direction).
     */
    List<SearchHit> search(float[] queryEmbedding, int topK);

    /** Removes every chunk of the given file. */
    void deleteFile(String fileId);

    /** Number of chunks currently stored. */
    long chunkCount();

    /** The stored files: file id → number of chunks. Default: empty. */
    default java.util.Map<String, Long> listFiles() {
        return java.util.Map.of();
    }

    /** One search result: the chunk and its cosine similarity. */
    record SearchHit(VectorChunk chunk, double score) {}
}
