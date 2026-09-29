package ai.mindconnect.vectorstore.embedding;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * A metadata key of one entity type that searches may compare by range or
 * list — "mail received after", "todo due before", "page between". Declared
 * once with {@link EmbeddingIndex#declareField}; the index then checks the
 * values written under the key and keeps a database index on it.
 *
 * <p>Equality on any key needs no declaration: {@link EmbeddingQuery#where}
 * works on every key and is served by the index on the whole metadata.
 *
 * @param type the entity type the field belongs to — {@code received_at} of mail, not of files
 * @param key  the metadata key: letters, digits, underscore, dash and dot
 * @param kind how values compare
 */
public record MetadataField(EntityType type, String key, Kind kind) {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * Timestamps are stored in one fixed UTC form, millisecond precision, so
     * that their text sorts the way the instants do.
     */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public enum Kind {
        /** Compared as text. */
        TEXT,
        /** Compared as a decimal number. */
        NUMBER,
        /** An ISO-8601 instant, compared in time. */
        TIMESTAMP
    }

    public MetadataField {
        if (type == null || kind == null) {
            throw new IllegalArgumentException("A metadata field needs an entity type and a kind");
        }
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("A metadata key is 1–64 letters, digits, '_', '-' or '.': '" + key + "'");
        }
    }

    /**
     * The value as it is stored: a number as written, an instant in the fixed
     * UTC form.
     *
     * @throws IllegalArgumentException when the value is no number or instant
     */
    public String normalise(String value) {
        return switch (kind) {
            case TEXT -> value;
            case NUMBER -> {
                try {
                    new java.math.BigDecimal(value.strip());
                    yield value.strip();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Metadata '" + key + "' of " + type + " is a number, not '" + value + "'");
                }
            }
            case TIMESTAMP -> {
                try {
                    yield TIMESTAMP.format(Instant.parse(value.strip()));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Metadata '" + key + "' of " + type
                            + " is an ISO-8601 instant (e.g. 2026-09-01T08:00:00Z), not '" + value + "'");
                }
            }
        };
    }

    /** How two stored values of this field order. */
    public Comparator<String> order() {
        return switch (kind) {
            case TEXT, TIMESTAMP -> Comparator.naturalOrder();
            case NUMBER -> Comparator.comparing(java.math.BigDecimal::new);
        };
    }
}
