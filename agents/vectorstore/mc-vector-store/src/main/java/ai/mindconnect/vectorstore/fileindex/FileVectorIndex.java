package ai.mindconnect.vectorstore.fileindex;

import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The embedded chunks of stored files, keyed by file and embedding model —
 * one index for every file, whichever data pool or chat session it belongs
 * to. A file is embedded once per model; putting it into another pool costs
 * nothing.
 *
 * <p>The index knows nothing about pools, sessions or owners. Whoever searches
 * passes the files to search in — a pool's or a session's file list, resolved
 * and access-checked by the service that owns that list. The index only
 * guarantees that nothing outside that list comes back.
 *
 * <p>A search always runs in two steps: first the chunks of the given files
 * (and model, and metadata) are selected, then only those are ranked by
 * cosine similarity. Filtering after a global nearest-neighbour search would
 * lose hits whenever the files are a small part of the index.
 *
 * <p>Like every persistence adapter, an implementation is bound to one
 * namespace; nothing on this interface carries one.
 */
public interface FileVectorIndex {

    /**
     * Replaces everything indexed for {@code file} under {@code embeddingModel}
     * with {@code chunks}, atomically; an empty list removes it. Chunk ids are
     * unique within the file. Every chunk's {@link VectorChunk#fileId()} must
     * be {@code file} and all share one dimension, otherwise
     * {@link IllegalArgumentException}.
     */
    void replaceFile(FileId file, String embeddingModel, List<VectorChunk> chunks);

    /** Removes the file's chunks for every model — the file is gone. */
    void deleteFile(FileId file);

    /** Number of chunks indexed for the file under the model. */
    long chunkCount(FileId file, String embeddingModel);

    /** Whether the file has been indexed under the model. */
    default boolean isIndexed(FileId file, String embeddingModel) {
        return chunkCount(file, embeddingModel) > 0;
    }

    /**
     * The {@code topK} chunks of {@code files}, embedded with
     * {@code embeddingModel}, most similar to {@code queryEmbedding}, best
     * first. No files, no hits — never a search across everything. Scores are
     * cosine similarities in {@code [-1, 1]}.
     *
     * @param metadata only chunks whose metadata contains every one of these
     *                 entries; empty for no restriction
     */
    List<FileHit> search(Set<FileId> files, String embeddingModel, float[] queryEmbedding, int topK,
                         Map<String, String> metadata);

    /** {@link #search(Set, String, float[], int, Map)} without a metadata restriction. */
    default List<FileHit> search(Set<FileId> files, String embeddingModel, float[] queryEmbedding, int topK) {
        return search(files, embeddingModel, queryEmbedding, topK, Map.of());
    }

    /** One search result: the file, the chunk (without its embedding), its cosine similarity. */
    record FileHit(FileId file, VectorChunk chunk, double score) {}
}
