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
     * @throws IllegalArgumentException for duplicate chunk ids, chunks without
     *                                  text, mixed or zero dimensions, an all-zero
     *                                  or non-finite vector, or a declared metadata
     *                                  field whose value does not fit its kind
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
     * @throws IllegalArgumentException for an all-zero or non-finite query vector,
     *                                  an attribute query that does not say whose
     *                                  entries it means, or a range or list filter
     *                                  on an undeclared metadata key
     */
    List<EmbeddingHit> search(EmbeddingQuery query, float[] queryEmbedding, int topK);

    /**
     * The entities passing {@code query}, without ranking — what an admin
     * screen lists, and how a caller learns what a pool or session has
     * indexed. Ordered by ref.
     */
    List<IndexedEntity> list(EmbeddingQuery query);

    /**
     * The chunks of the entities passing {@code query} — text and metadata,
     * without vectors — ordered by entity and position, one page of them, for
     * reading what an entity was cut into.
     *
     * @param text   only chunks whose text contains this, ignoring case; {@code null} for all
     * @param offset how many matching chunks to skip
     * @param limit  at most how many to return
     */
    ChunkPage chunks(EmbeddingQuery query, String text, int offset, int limit);

    /**
     * Declares a metadata key of one entity type that searches may compare by
     * range or list. Idempotent; declaring a key again with another kind is
     * refused. Values written under the key from now on must fit its kind; the
     * Postgres index also keeps a database index on it. Modules declare their
     * fields once at startup — mail its {@code received_at}, todos their
     * {@code due}. A declaration is not bound to a namespace: in Postgres it
     * holds for the whole table, as the database index on it does.
     */
    void declareField(MetadataField field);

    /** The declared fields. */
    List<MetadataField> fields();

    /** One search result: the entity, its owner, the chunk (without its vector), its cosine similarity. */
    record EmbeddingHit(EntityRef ref, UserId owner, EmbeddingChunk chunk, double score) {}

    /** One chunk as {@link #chunks} lists it: the entity it belongs to and the chunk, without its vector. */
    record IndexedChunk(EntityRef ref, EmbeddingChunk chunk) {}

    /** One page of {@link #chunks}, and how many chunks match in all. */
    record ChunkPage(List<IndexedChunk> chunks, long total) {}

    /** One indexed entity under one model: whose it is, which version, how many chunks. */
    record IndexedEntity(EntityRef ref, UserId owner, String embeddingModel, String version, long chunks) {}
}
