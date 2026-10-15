package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.embedding.EmbeddingChunk;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.EmbeddingHit;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.IndexedEntity;
import ai.mindconnect.vectorstore.embedding.EmbeddingQuery;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import ai.mindconnect.vectorstore.embedding.MetadataFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One named store as a caller works with it: the entities it lists, searched
 * in the namespace's {@link EmbeddingIndex} under the store's embedding model.
 * Obtained from {@link VectorStores#open} or {@link VectorStores#store}; cheap,
 * holds no data of its own.
 *
 * <p>A search only ever sees the store's members — the ref list is the first
 * step of the index's two-step search — and may narrow down further: to some
 * of the members, by metadata.
 */
public final class VectorStore {

    /** One piece of text to embed, with the metadata a search can filter on. */
    public record TextChunk(String text, Map<String, String> metadata) {
        public TextChunk {
            metadata = metadata == null ? Map.of() : metadata;
        }
    }

    private final VectorStoreInstance settings;
    private final VectorStoreRegistry registry;
    private final EmbeddingIndex index;
    /** Resolved on first need: listing the members must work without an embedding config. */
    private final Supplier<String> model;
    private final Function<List<String>, List<float[]>> embedder;
    private volatile String embeddingModel;

    VectorStore(VectorStoreInstance settings, VectorStoreRegistry registry, EmbeddingIndex index,
                Supplier<String> model, Function<List<String>, List<float[]>> embedder) {
        this.settings = settings;
        this.registry = registry;
        this.index = index;
        this.model = model;
        this.embedder = embedder;
    }

    public String name() {
        return settings.name();
    }

    public VectorStoreInstance settings() {
        return settings;
    }

    /** The key the index keeps this store's vectors under — the embedding model behind the store's config. */
    public String embeddingModel() {
        if (embeddingModel == null) {
            embeddingModel = model.get();
        }
        return embeddingModel;
    }

    /**
     * A {@link EntityType#DOCUMENT} of this store: text written straight into it
     * (by {@code vector_upsert}, say) with no file behind it. Its id is the
     * caller's; the store's key keeps two stores' documents apart.
     */
    public EntityRef documentRef(String id) {
        return EntityRef.of(EntityType.DOCUMENT, VectorStoreRegistry.key(name()), id);
    }

    public List<EntityRef> members() {
        return registry.members(name());
    }

    /**
     * Embeds {@code chunks} as {@code ref}'s text and lists {@code ref} in this
     * store. When the index already holds {@code ref} at {@code version} under
     * this store's model — the same file attached to a second chat, the same
     * document ingested twice — nothing is embedded again.
     *
     * @param owner the user the entity belongs to; {@code null} for the namespace
     * @return how many chunks the entity has in the index
     */
    public long put(EntityRef ref, UserId owner, String version, List<TextChunk> chunks) {
        if (!version.equals(index.indexedVersion(ref, embeddingModel()).orElse(null))) {
            List<float[]> vectors = chunks.isEmpty() ? List.of()
                    : embedder.apply(chunks.stream().map(TextChunk::text).toList());
            List<EmbeddingChunk> embedded = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                embedded.add(new EmbeddingChunk("c" + i, i, chunks.get(i).text(), chunks.get(i).metadata(),
                        vectors.get(i)));
            }
            index.replace(ref, owner, version, embeddingModel(), embedded);
        }
        registry.addMember(name(), ref);
        return index.chunkCount(ref, embeddingModel());
    }

    /**
     * Whether the index holds {@code ref} under this store's model — then listing
     * it costs no embedding. Without an embedding config the store can have
     * indexed nothing: false, and whoever goes on to embed gets the config's error.
     */
    public boolean indexed(EntityRef ref) {
        String model;
        try {
            model = embeddingModel();
        } catch (IllegalStateException e) {
            return false;
        }
        return index.indexedVersion(ref, model).isPresent();
    }

    /** Lists an entity that is — or will be — in the index, without embedding anything. */
    public void add(EntityRef ref) {
        registry.addMember(name(), ref);
    }

    /**
     * Takes {@code ref} off this store. A document of this store, which no other
     * store can list, leaves the index with it; a file or a mail stays there for
     * the other stores and chats that list it.
     */
    public void remove(EntityRef ref) {
        registry.removeMember(name(), ref);
        if (ref.type().equals(EntityType.DOCUMENT) && registry.storesListing(ref).isEmpty()) {
            index.delete(ref);
        }
    }

    /** The members that any of {@code selectors} picks. */
    public Set<EntityRef> select(List<EntitySelector> selectors) {
        Set<EntityRef> picked = new LinkedHashSet<>();
        for (EntityRef member : members()) {
            if (selectors.stream().anyMatch(s -> s.matches(member))) {
                picked.add(member);
            }
        }
        return picked;
    }

    /** The members as the index knows them: owner, version, chunk count. */
    public List<IndexedEntity> entities() {
        List<EntityRef> members = members();
        return members.isEmpty() ? List.of() : index.list(EmbeddingQuery.of(embeddingModel(), members));
    }

    /**
     * One page of the chunks of this store's members — among {@code within} only
     * ({@code null} for all), whose text contains {@code text} ({@code null} for all).
     */
    public EmbeddingIndex.ChunkPage chunks(Set<EntityRef> within, String text, int offset, int limit) {
        Set<EntityRef> refs = new LinkedHashSet<>(members());
        if (within != null) {
            refs.retainAll(within);
        }
        if (refs.isEmpty()) {
            return new EmbeddingIndex.ChunkPage(List.of(), 0);
        }
        return index.chunks(EmbeddingQuery.of(embeddingModel(), refs), text, offset, limit);
    }

    public long chunkCount() {
        return entities().stream().mapToLong(IndexedEntity::chunks).sum();
    }

    /** The {@code topK} chunks of this store's members most similar to {@code query}. */
    public List<EmbeddingHit> search(String query, int topK) {
        return search(query, topK, null, List.of());
    }

    /**
     * The {@code topK} chunks of this store's members most similar to
     * {@code query}, among {@code within} only (those of them the store lists;
     * {@code null} for all members) and passing {@code filters}.
     */
    public List<EmbeddingHit> search(String query, int topK, Set<EntityRef> within, List<MetadataFilter> filters) {
        Set<EntityRef> refs = new LinkedHashSet<>(members());
        if (within != null) {
            refs.retainAll(within);
        }
        if (refs.isEmpty()) {
            return List.of();
        }
        EmbeddingQuery q = EmbeddingQuery.of(embeddingModel(), refs);
        for (MetadataFilter filter : filters) {
            q = q.with(filter);
        }
        return index.search(q, embedder.apply(List.of(query)).get(0), topK);
    }
}
