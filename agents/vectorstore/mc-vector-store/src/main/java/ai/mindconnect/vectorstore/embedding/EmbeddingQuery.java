package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;

import java.util.Collection;
import java.util.HashMap;
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
 *       finds nothing. Whoever built the list has decided the caller may read
 *       it, so no owner is required.</li>
 *   <li><b>by attributes</b> — types, owner, source, container: "Alice's mail in
 *       {@code email.freemail}/{@code INBOX}". At least one type is required, so
 *       there is no search across everything, and the query must say whose
 *       entries it means — {@link #owner}, {@link #ownerOrShared},
 *       {@link #sharedOnly} or, deliberately, {@link #anyOwner}. Account keys
 *       such as {@code email.freemail} are per user, not unique across users;
 *       a forgotten owner would otherwise return everybody's mail.</li>
 * </ul>
 *
 * @param embeddingModel the model the query vector was made with; only entries of that model are compared
 * @param refs           only these entities; {@code null} for no restriction by ref
 * @param types          only these kinds; empty for any
 * @param owners         only entries owned by one of these; empty for no restriction by user
 * @param shared         also (or, without owners, only) entries that belong to no user
 * @param everyOwner     explicitly every owner's entries — needed for an attribute
 *                       query without owners and without {@code shared}
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
        boolean everyOwner,
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
        if (everyOwner && (shared || !owners.isEmpty())) {
            throw new IllegalArgumentException("anyOwner() and an owner restriction contradict each other");
        }
        if (container != null && source == null) {
            throw new IllegalArgumentException("A container is only meaningful within a source");
        }
    }

    /** Exactly these entities — a pool's or a session's files, say. */
    public static EmbeddingQuery of(String embeddingModel, Collection<EntityRef> refs) {
        return new EmbeddingQuery(embeddingModel, Set.copyOf(refs), null, null, false, false, null, null, null);
    }

    /**
     * Entities of these types; narrow down with the withers. Before it is
     * searched, an attribute query needs {@link #owner}, {@link #ownerOrShared},
     * {@link #sharedOnly} or {@link #anyOwner}.
     */
    public static EmbeddingQuery ofTypes(String embeddingModel, String... types) {
        return new EmbeddingQuery(embeddingModel, null, Set.of(types), null, false, false, null, null, null);
    }

    /** Only what {@code user} owns. */
    public EmbeddingQuery owner(UserId user) {
        return new EmbeddingQuery(embeddingModel, refs, types, Set.of(user), false, false, source, container, metadata);
    }

    /** What {@code user} owns plus what belongs to nobody in particular. */
    public EmbeddingQuery ownerOrShared(UserId user) {
        return new EmbeddingQuery(embeddingModel, refs, types, Set.of(user), true, false, source, container, metadata);
    }

    /** Only what belongs to nobody in particular — the namespace's shared entries. */
    public EmbeddingQuery sharedOnly() {
        return new EmbeddingQuery(embeddingModel, refs, types, null, true, false, source, container, metadata);
    }

    /** Every owner's entries — for admin views and maintenance, never on a user's behalf. */
    public EmbeddingQuery anyOwner() {
        return new EmbeddingQuery(embeddingModel, refs, types, null, false, true, source, container, metadata);
    }

    public EmbeddingQuery in(String source) {
        return new EmbeddingQuery(embeddingModel, refs, types, owners, shared, everyOwner, source, null, metadata);
    }

    public EmbeddingQuery in(String source, String container) {
        return new EmbeddingQuery(embeddingModel, refs, types, owners, shared, everyOwner, source, container, metadata);
    }

    public EmbeddingQuery where(String key, String value) {
        Map<String, String> more = new HashMap<>(metadata);
        more.put(key, value);
        return new EmbeddingQuery(embeddingModel, refs, types, owners, shared, everyOwner, source, container, more);
    }

    /**
     * Throws unless the query says whose entries it means — called by every
     * index before it searches, so a forgotten owner fails loudly instead of
     * returning other users' entries.
     */
    public void requireOwnerDecision() {
        if (refs == null && owners.isEmpty() && !shared && !everyOwner) {
            throw new IllegalArgumentException("An attribute search must say whose entries it means: "
                    + "owner(...), ownerOrShared(...), sharedOnly() or anyOwner()");
        }
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
}
