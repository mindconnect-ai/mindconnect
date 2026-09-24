package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.Vectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EmbeddingIndex} on the heap, without persistence — for tests and for
 * embedding hosts that re-index on start. Searches the same way the Postgres
 * index does: select what the query names, then rank exactly.
 *
 * <p>Vectors are copied and normalised on the way in, so a search is a dot
 * product and a caller reusing its buffers cannot change what is indexed.
 * Writes are serialised; reads see either the old or the new entries of an
 * entity, never a mix.
 */
public final class MemoryEmbeddingIndex implements EmbeddingIndex {

    /** What one entity has under one model; immutable, replaced as a whole. */
    private record Indexed(UserId owner, String version, List<EmbeddingChunk> chunks) {}

    /** Entity → model → entries. The inner maps are immutable and replaced as a whole. */
    private final Map<EntityRef, Map<String, Indexed>> entities = new ConcurrentHashMap<>();

    @Override
    public synchronized void replace(EntityRef ref, UserId owner, String version, String embeddingModel,
                                     List<EmbeddingChunk> chunks) {
        EmbeddingChecks.checkReplace(ref, version, embeddingModel, chunks);
        Map<String, Indexed> models = new HashMap<>(entities.getOrDefault(ref, Map.of()));
        if (chunks.isEmpty()) {
            models.remove(embeddingModel);
        } else {
            List<EmbeddingChunk> normalised = chunks.stream()
                    .map(chunk -> chunk.withEmbedding(Vectors.normalised(chunk.embedding())))
                    .toList();
            models.put(embeddingModel, new Indexed(owner, version, normalised));
        }
        if (models.isEmpty()) {
            entities.remove(ref);
        } else {
            entities.put(ref, Map.copyOf(models));
        }
    }

    @Override
    public synchronized void delete(EntityRef ref) {
        entities.remove(ref);
    }

    @Override
    public synchronized void relocate(EntityRef from, EntityRef to) {
        EmbeddingChecks.checkRelocate(from, to);
        if (from.equals(to)) {
            return;
        }
        Map<String, Indexed> models = entities.remove(from);
        if (models != null) {
            entities.put(to, models);
        }
    }

    @Override
    public Optional<String> indexedVersion(EntityRef ref, String embeddingModel) {
        return Optional.ofNullable(entities.getOrDefault(ref, Map.of()).get(embeddingModel)).map(Indexed::version);
    }

    @Override
    public long chunkCount(EntityRef ref, String embeddingModel) {
        Indexed indexed = entities.getOrDefault(ref, Map.of()).get(embeddingModel);
        return indexed == null ? 0 : indexed.chunks().size();
    }

    @Override
    public List<EmbeddingHit> search(EmbeddingQuery query, float[] queryEmbedding, int topK) {
        EmbeddingChecks.requireUsable(queryEmbedding, "The query");
        query.requireOwnerDecision();
        if (topK <= 0) {
            return List.of();
        }
        float[] normalisedQuery = Vectors.normalised(queryEmbedding);
        Iterable<EntityRef> candidates = query.refs() != null ? query.refs() : entities.keySet();
        List<EmbeddingHit> hits = new ArrayList<>();
        for (EntityRef ref : candidates) {
            Indexed indexed = entities.getOrDefault(ref, Map.of()).get(query.embeddingModel());
            if (indexed == null) {
                continue;
            }
            for (EmbeddingChunk chunk : indexed.chunks()) {
                if (chunk.embedding().length == normalisedQuery.length && query.matches(ref, indexed.owner(), chunk)) {
                    hits.add(new EmbeddingHit(ref, indexed.owner(), chunk.withEmbedding(new float[0]),
                            Vectors.dot(normalisedQuery, chunk.embedding())));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(EmbeddingHit::score).reversed());
        return List.copyOf(hits.subList(0, Math.min(topK, hits.size())));
    }
}
