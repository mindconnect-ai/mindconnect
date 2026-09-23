package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The first step of every search: which entries come into question at all.
 * Only those are ranked against the query vector.
 *
 * <p>Two ways to narrow down, combinable:
 * <ul>
 *   <li><b>by refs</b> — the caller knows the entities: a data pool's or a chat
 *       session's files, the three events it is talking about. An empty list
 *       finds nothing.</li>
 *   <li><b>by attributes</b> — types, owner, source, container: "Alice's mail in
 *       {@code email.freemail}/{@code INBOX}". At least one type is required, so
 *       there is no search across everything.</li>
 * </ul>
 *
 * <p>The index trusts the query: whoever builds it has decided what the caller
 * may see — typically by passing the caller as owner, or by building the ref
 * list from a pool or session the caller may read.
 *
 * @param embeddingModel the model the query vector was made with; only entries of that model are compared
 * @param refs           only these entities; {@code null} for no restriction by ref
 * @param types          only these kinds; empty for any
 * @param owners         only entries owned by one of these; empty for any owner
 * @param shared         also (or, without owners, only) entries that belong to no user
 * @param source         only this source; {@code null} for any
 * @param container      only this container of {@code source}; {@code null} for any
 * @param metadata       only chunks whose metadata contains every one of these entries
 */
public record EmbeddingQuery(
        String embeddingModel,
        Set<EntityRef> refs,
        Set<String> types,
        Set<UserId> owners,
        boolean shared,
        String source,
        String container,
        Map<String, String> metadata
) {

    public EmbeddingQuery {
        EmbeddingChecks.requireModel(embeddingModel);
        refs = refs == null ? null : Set.copyOf(refs);
        types = types == null ? Set.of() : Set.copyOf(types);
        owners = owners == null ? Set.of() : Set.copyOf(owners);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (refs == null && types.isEmpty()) {
            throw new IllegalArgumentException("A search names its entities or at least one entity type");
        }
        if (container != null && source == null) {
            throw new IllegalArgumentException("A container is only meaningful within a source");
        }
    }

    /** Exactly these entities — a pool's or a session's files, say. */
    public static EmbeddingQuery of(String embeddingModel, Collection<EntityRef> refs) {
        return new EmbeddingQuery(embeddingModel, Set.copyOf(refs), null, null, false, null, null, null);
    }

    /** Every entity of these types; narrow down further with the withers. */
    public static EmbeddingQuery ofTypes(String embeddingModel, String... types) {
        return new EmbeddingQuery(embeddingModel, null, Set.of(types), null, false, null, null, null);
    }

    /** Only what {@code user} owns. */
    public EmbeddingQuery owner(UserId user) {
        return new EmbeddingQuery(embeddingModel, refs, types, Set.of(user), false, source, container, metadata);
    }

    /** What {@code user} owns plus what belongs to nobody in particular. */
    public EmbeddingQuery ownerOrShared(UserId user) {
        return new EmbeddingQuery(embeddingModel, refs, types, Set.of(user), true, source, container, metadata);
    }

    public EmbeddingQuery in(String source) {
        return new EmbeddingQuery(embeddingModel, refs, types, owners, shared, source, null, metadata);
    }

    public EmbeddingQuery in(String source, String container) {
        return new EmbeddingQuery(embeddingModel, refs, types, owners, shared, source, container, metadata);
    }

    public EmbeddingQuery where(String key, String value) {
        Map<String, String> more = new HashMap<>(metadata);
        more.put(key, value);
        return new EmbeddingQuery(embeddingModel, refs, types, owners, shared, source, container, more);
    }

    /** Whether an entry of {@code ref}, owned by {@code owner}, with this chunk, passes. */
    public boolean matches(EntityRef ref, UserId owner, EmbeddingChunk chunk) {
        return (refs == null || refs.contains(ref))
                && (types.isEmpty() || types.contains(ref.type()))
                && ownerMatches(owner)
                && (source == null || source.equals(ref.source()))
                && (container == null || container.equals(ref.container()))
                && chunk.metadata().entrySet().containsAll(metadata.entrySet());
    }

    private boolean ownerMatches(UserId owner) {
        if (owners.isEmpty()) {
            return !shared || owner == null;
        }
        return owner == null ? shared : owners.contains(owner);
    }

    /** The owner ids as the index stores them. */
    public List<String> ownerIds() {
        return owners.stream().map(UserId::value).toList();
    }
}
