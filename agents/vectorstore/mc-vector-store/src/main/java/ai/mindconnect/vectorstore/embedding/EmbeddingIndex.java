package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;

/**
 * The embedded text of every entity we know — files, mail, calendar events,
 * todos, drive items — in one index, keyed by {@link EntityRef} and embedding
 * model. An entity is embedded once per model; putting a file into another
 * data pool or chat session costs nothing.
 *
 * <p>The index knows no pools, sessions or permissions. A search takes an
 * {@link EmbeddingQuery} that names the entities or narrows them down by type,
 * owner, source and container; the index guarantees only that nothing outside
 * that comes back.
 *
 * <p>A search always runs in two steps: first the entries passing the query
 * are selected, then only those are ranked by cosine similarity. Filtering
 * after a global nearest-neighbour search would lose hits whenever the
 * selection is a small part of the index.
 *
 * <p>Like every persistence adapter, an implementation is bound to one
 * namespace; nothing on this interface carries one.
 */
public interface EmbeddingIndex {

    /**
     * Replaces everything indexed for {@code ref} under {@code embeddingModel}
     * with {@code chunks}, atomically and serialised against other writes to
     * the same entity; an empty list removes it.
     *
     * @param owner   the user the entity belongs to; {@code null} for the namespace as a whole
     * @param version whatever changes when the text does — an ETag, a hash, a
     *                revision; a constant for entities that never change. Read
     *                back with {@link #indexedVersion} to skip re-embedding.
     * @throws IllegalArgumentException for duplicate chunk ids, mixed or zero
     *                                  dimensions, or an all-zero vector
     */
    void replace(EntityRef ref, UserId owner, String version, String embeddingModel, List<EmbeddingChunk> chunks);

    /** Removes the entity's entries for every model — the entity is gone. */
    void delete(EntityRef ref);

    /**
     * The entity now lies elsewhere or goes by another id — a mail moved to
     * another folder gets a new UID there. Its entries for every model move
     * along without being embedded again; whatever was indexed under
     * {@code to} is replaced. Both refs must name the same type and source.
     */
    void relocate(EntityRef from, EntityRef to);

    /** The version the entity was indexed with under the model; empty when it is not indexed. */
    Optional<String> indexedVersion(EntityRef ref, String embeddingModel);

    /** Number of chunks indexed for the entity under the model. */
    long chunkCount(EntityRef ref, String embeddingModel);

    /**
     * The {@code topK} chunks passing {@code query} most similar to
     * {@code queryEmbedding}, best first. Only chunks of the query's model and
     * dimension are compared. Scores are cosine similarities in {@code [-1, 1]}.
     *
     * @throws IllegalArgumentException for an all-zero query vector
     */
    List<EmbeddingHit> search(EmbeddingQuery query, float[] queryEmbedding, int topK);

    /** One search result: the entity, its owner, the chunk (without its vector), its cosine similarity. */
    record EmbeddingHit(EntityRef ref, UserId owner, EmbeddingChunk chunk, double score) {}
}
