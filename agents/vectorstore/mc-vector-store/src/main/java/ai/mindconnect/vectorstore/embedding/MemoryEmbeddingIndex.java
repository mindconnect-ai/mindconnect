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
 * {@link EmbeddingIndex} on the heap. Without an {@link EntryStore} it keeps
 * nothing across restarts — for tests and hosts that re-index on start; with
 * one ({@link FileEntryStore}) every write lands on disk first and the index
 * is reloaded from there on start.
 *
 * <p>Searches the same way the Postgres index does: select what the query
 * names, then rank exactly. Vectors are copied and normalised on the way in,
 * so a search is a dot product and a caller reusing its buffers cannot change
 * what is indexed. Writes are serialised; reads see either the old or the new
 * entries of an entity, never a mix.
 */
public final class MemoryEmbeddingIndex implements EmbeddingIndex {

    /** Entity → model → entry. The inner maps are immutable and replaced as a whole. */
    private final Map<EntityRef, Map<String, EntryStore.Entry>> entities = new ConcurrentHashMap<>();
    private final MetadataFields fields = new MetadataFields();
    private final EntryStore store;

    /** Nothing kept across restarts. */
    public MemoryEmbeddingIndex() {
        this(EntryStore.NONE);
    }

    /** Everything in {@code store}, loaded now. */
    public MemoryEmbeddingIndex(EntryStore store) {
        this.store = store;
        store.fields().forEach(fields::declare);
        store.entries().forEach(entry ->
                entities.merge(entry.ref(), Map.of(entry.embeddingModel(), entry), (a, b) -> {
                    Map<String, EntryStore.Entry> both = new HashMap<>(a);
                    both.putAll(b);
                    return Map.copyOf(both);
                }));
    }

    @Override
    public synchronized void replace(EntityRef ref, UserId owner, String version, String embeddingModel,
                                     List<EmbeddingChunk> chunks) {
        EmbeddingChecks.checkReplace(ref, version, embeddingModel, chunks);
        Map<String, EntryStore.Entry> models = new HashMap<>(entities.getOrDefault(ref, Map.of()));
        if (chunks.isEmpty()) {
            if (models.remove(embeddingModel) != null) {
                store.remove(ref, embeddingModel);
            }
        } else {
            List<EmbeddingChunk> stored = chunks.stream()
                    .map(chunk -> new EmbeddingChunk(chunk.id(), chunk.ordinal(), chunk.text(),
                            fields.normalise(ref.type(), chunk.metadata()), Vectors.normalised(chunk.embedding())))
                    .toList();
            EntryStore.Entry entry = new EntryStore.Entry(ref, embeddingModel, owner, version, stored);
            store.write(entry);
            models.put(embeddingModel, entry);
        }
        if (models.isEmpty()) {
            entities.remove(ref);
        } else {
            entities.put(ref, Map.copyOf(models));
        }
    }

    @Override
    public synchronized void delete(EntityRef ref) {
        Map<String, EntryStore.Entry> models = entities.remove(ref);
        if (models != null) {
            models.keySet().forEach(model -> store.remove(ref, model));
        }
    }

    @Override
    public synchronized void relocate(EntityRef from, EntityRef to) {
        EmbeddingChecks.checkRelocate(from, to);
        if (from.equals(to) || !entities.containsKey(from)) {
            return;
        }
        delete(to);
        Map<String, EntryStore.Entry> moved = new HashMap<>();
        for (EntryStore.Entry entry : entities.remove(from).values()) {
            EntryStore.Entry there = new EntryStore.Entry(to, entry.embeddingModel(), entry.owner(),
                    entry.version(), entry.chunks());
            store.write(there);
            store.remove(from, entry.embeddingModel());
            moved.put(entry.embeddingModel(), there);
        }
        entities.put(to, Map.copyOf(moved));
    }

    @Override
    public Optional<String> indexedVersion(EntityRef ref, String embeddingModel) {
        return Optional.ofNullable(entities.getOrDefault(ref, Map.of()).get(embeddingModel))
                .map(EntryStore.Entry::version);
    }

    @Override
    public long chunkCount(EntityRef ref, String embeddingModel) {
        EntryStore.Entry entry = entities.getOrDefault(ref, Map.of()).get(embeddingModel);
        return entry == null ? 0 : entry.chunks().size();
    }

    @Override
    public List<EmbeddingHit> search(EmbeddingQuery query, float[] queryEmbedding, int topK) {
        EmbeddingChecks.requireUsable(queryEmbedding, "The query");
        query.requireOwnerDecision();
        List<MetadataFields.ResolvedFilter> filters = fields.resolve(query);
        if (topK <= 0) {
            return List.of();
        }
        float[] normalisedQuery = Vectors.normalised(queryEmbedding);
        List<EmbeddingHit> hits = new ArrayList<>();
        for (EntryStore.Entry entry : candidates(query)) {
            for (EmbeddingChunk chunk : entry.chunks()) {
                if (chunk.embedding().length == normalisedQuery.length
                        && MetadataFields.matches(filters, chunk.metadata())) {
                    hits.add(new EmbeddingHit(entry.ref(), entry.owner(), chunk.withEmbedding(new float[0]),
                            Vectors.dot(normalisedQuery, chunk.embedding())));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(EmbeddingHit::score).reversed());
        return List.copyOf(hits.subList(0, Math.min(topK, hits.size())));
    }

    @Override
    public List<IndexedEntity> list(EmbeddingQuery query) {
        query.requireOwnerDecision();
        List<MetadataFields.ResolvedFilter> filters = fields.resolve(query);
        List<IndexedEntity> listed = new ArrayList<>();
        for (EntryStore.Entry entry : candidates(query)) {
            long passing = entry.chunks().stream().filter(c -> MetadataFields.matches(filters, c.metadata())).count();
            if (passing > 0) {
                listed.add(new IndexedEntity(entry.ref(), entry.owner(), entry.embeddingModel(), entry.version(),
                        entry.chunks().size()));
            }
        }
        listed.sort(Comparator.comparing(e -> e.ref().toString()));
        return listed;
    }

    @Override
    public ChunkPage chunks(EmbeddingQuery query, String text, int offset, int limit) {
        query.requireOwnerDecision();
        List<MetadataFields.ResolvedFilter> filters = fields.resolve(query);
        String needle = text == null || text.isBlank() ? null : text.toLowerCase(java.util.Locale.ROOT);
        List<EntryStore.Entry> entries = new ArrayList<>(candidates(query));
        entries.sort(Comparator.comparing(e -> e.ref().toString()));
        List<IndexedChunk> matching = new ArrayList<>();
        for (EntryStore.Entry entry : entries) {
            entry.chunks().stream()
                    .filter(c -> MetadataFields.matches(filters, c.metadata()))
                    .filter(c -> needle == null || c.text().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .sorted(Comparator.comparingInt(EmbeddingChunk::ordinal))
                    .forEach(c -> matching.add(new IndexedChunk(entry.ref(), c.withEmbedding(new float[0]))));
        }
        int from = Math.min(Math.max(offset, 0), matching.size());
        int to = Math.min(from + Math.max(limit, 0), matching.size());
        return new ChunkPage(List.copyOf(matching.subList(from, to)), matching.size());
    }

    @Override
    public synchronized void declareField(MetadataField field) {
        if (fields.declare(field)) {
            store.writeFields(fields.all());
        }
    }

    @Override
    public List<MetadataField> fields() {
        return fields.all();
    }

    /** The entries under the query's model whose entity passes it, before any metadata is looked at. */
    private List<EntryStore.Entry> candidates(EmbeddingQuery query) {
        Iterable<EntityRef> refs = query.refs() != null ? query.refs() : entities.keySet();
        List<EntryStore.Entry> candidates = new ArrayList<>();
        for (EntityRef ref : refs) {
            EntryStore.Entry entry = entities.getOrDefault(ref, Map.of()).get(query.embeddingModel());
            if (entry != null && query.matchesEntity(ref, entry.owner())) {
                candidates.add(entry);
            }
        }
        return candidates;
    }
}
