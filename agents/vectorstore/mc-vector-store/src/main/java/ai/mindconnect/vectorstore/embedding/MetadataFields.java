package ai.mindconnect.vectorstore.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The declared {@link MetadataField}s of one index, and what follows from
 * them: values normalised on the way in, filters checked before a search,
 * and — for the heap indexes — the comparison itself.
 */
public final class MetadataFields {

    /** (type, key) → field. */
    private final Map<String, MetadataField> fields = new ConcurrentHashMap<>();

    private static String id(EntityType type, String key) {
        return type.value() + "\u001f" + key;
    }

    /**
     * Adds a declaration; declaring the same field again is fine, declaring
     * the key of a type with another kind is not.
     *
     * @return whether the field is new
     */
    public boolean declare(MetadataField field) {
        MetadataField known = fields.putIfAbsent(id(field.type(), field.key()), field);
        if (known != null && known.kind() != field.kind()) {
            throw new IllegalArgumentException("Metadata '" + field.key() + "' of " + field.type()
                    + " is declared as " + known.kind() + ", not " + field.kind());
        }
        return known == null;
    }

    public List<MetadataField> all() {
        return List.copyOf(fields.values());
    }

    public Optional<MetadataField> field(EntityType type, String key) {
        return Optional.ofNullable(fields.get(id(type, key)));
    }

    /**
     * The chunk metadata as stored for an entity of {@code type}: declared
     * fields normalised, everything else as given.
     *
     * @throws IllegalArgumentException when a declared field's value does not fit its kind
     */
    public Map<String, String> normalise(EntityType type, Map<String, String> metadata) {
        Map<String, String> stored = null;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            MetadataField field = fields.get(id(type, entry.getKey()));
            if (field == null) {
                continue;
            }
            String normalised = field.normalise(entry.getValue());
            if (!normalised.equals(entry.getValue())) {
                if (stored == null) stored = new HashMap<>(metadata);
                stored.put(entry.getKey(), normalised);
            }
        }
        return stored == null ? metadata : Map.copyOf(stored);
    }

    /**
     * The query's filters with their values normalised, after checking that
     * every filter which needs a declared field has one for each entity type
     * the query can reach — the query's types, or else the types of its refs.
     *
     * @throws IllegalArgumentException for a range or list filter on an undeclared key,
     *                                  or a value that does not fit the field
     */
    public List<ResolvedFilter> resolve(EmbeddingQuery query) {
        Set<EntityType> reachable = !query.types().isEmpty() ? query.types()
                : query.refs() == null ? Set.of()
                : query.refs().stream().map(EntityRef::type).collect(Collectors.toSet());
        List<ResolvedFilter> resolved = new ArrayList<>();
        for (MetadataFilter filter : query.filters()) {
            if (!filter.needsField()) {
                resolved.add(new ResolvedFilter(filter, null));
                continue;
            }
            MetadataField first = null;
            for (EntityType type : reachable) {
                MetadataField field = fields.get(id(type, filter.key()));
                if (field == null) {
                    throw new IllegalArgumentException("Metadata '" + filter.key() + "' is not declared for " + type
                            + " — declare it as a MetadataField to filter it with " + filter.op());
                }
                if (first != null && first.kind() != field.kind()) {
                    throw new IllegalArgumentException("Metadata '" + filter.key() + "' has different kinds for "
                            + first.type() + " and " + type + "; search them separately");
                }
                first = field;
            }
            if (first == null) {
                throw new IllegalArgumentException("Metadata filter " + filter.op() + " on '" + filter.key()
                        + "' needs the entity types it applies to");
            }
            MetadataField field = first;
            List<String> values = filter.values().stream().map(field::normalise).toList();
            resolved.add(new ResolvedFilter(new MetadataFilter(filter.key(), filter.op(), values), field));
        }
        return resolved;
    }

    /** Whether chunk metadata passes every resolved filter — the heap indexes' comparison. */
    public static boolean matches(List<ResolvedFilter> filters, Map<String, String> metadata) {
        for (ResolvedFilter resolved : filters) {
            MetadataFilter filter = resolved.filter();
            String value = metadata.get(filter.key());
            if (value == null) {
                return false;
            }
            boolean ok;
            try {
                ok = switch (filter.op()) {
                    case EQ -> value.equals(filter.value());
                    case IN -> filter.values().stream().anyMatch(v -> resolved.field().order().compare(value, v) == 0);
                    case GTE -> resolved.field().order().compare(value, filter.value()) >= 0;
                    case LTE -> resolved.field().order().compare(value, filter.value()) <= 0;
                };
            } catch (NumberFormatException e) {
                // Written before the field was declared, and no number: it matches no range.
                ok = false;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** A filter with normalised values and, unless it is {@code EQ}, the field it compares by. */
    public record ResolvedFilter(MetadataFilter filter, MetadataField field) {}
}
