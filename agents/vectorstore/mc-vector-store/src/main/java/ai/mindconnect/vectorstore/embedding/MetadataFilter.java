package ai.mindconnect.vectorstore.embedding;

import java.util.List;

/**
 * One condition on a chunk's metadata.
 *
 * <ul>
 *   <li>{@link Op#EQ} works on every key.</li>
 *   <li>{@link Op#IN}, {@link Op#GTE} and {@link Op#LTE} need the key declared
 *       as a {@link MetadataField} for every entity type the search can reach;
 *       the index refuses them otherwise, so a missing database index shows up
 *       as an error instead of a slow scan.</li>
 * </ul>
 *
 * @param key    the metadata key
 * @param op     how the chunk's value is compared
 * @param values one value, or the list for {@link Op#IN}
 */
public record MetadataFilter(String key, Op op, List<String> values) {

    public enum Op {
        /** Equal to the one value. */
        EQ,
        /** Equal to one of the values. */
        IN,
        /** At or after / at least the one value. */
        GTE,
        /** At or before / at most the one value. */
        LTE
    }

    public MetadataFilter {
        if (key == null || key.isBlank() || op == null) {
            throw new IllegalArgumentException("A metadata filter needs a key and an operator");
        }
        values = values == null ? List.of() : List.copyOf(values);
        if (values.isEmpty() || (op != Op.IN && values.size() != 1)) {
            throw new IllegalArgumentException("Metadata filter on '" + key + "': " + op
                    + (op == Op.IN ? " needs at least one value" : " takes exactly one value"));
        }
    }

    /** The one value of every operator but {@link Op#IN}. */
    public String value() {
        return values.get(0);
    }

    /** Whether it needs the key declared as a {@link MetadataField}. */
    public boolean needsField() {
        return op != Op.EQ;
    }
}
