package ai.mindconnect.vectorstore.embedding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.regex.Pattern;

/**
 * The kind of entity an {@link EntityRef} names. The index does not know the
 * kinds: the constants here are the ones this repository indexes, and a module
 * that brings another kind names it with {@link #of} — lower case, digits and
 * dashes.
 */
public record EntityType(@JsonValue String value) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** A stored file, by its {@code FileId}. */
    public static final EntityType FILE = new EntityType("file");
    /** Text written straight into a vector store — by {@code vector_upsert}, say — with no file behind it. */
    public static final EntityType DOCUMENT = new EntityType("document");
    public static final EntityType MAIL = new EntityType("mail");
    public static final EntityType CALENDAR_EVENT = new EntityType("calendar-event");
    public static final EntityType TODO = new EntityType("todo");
    public static final EntityType DRIVE_ITEM = new EntityType("drive-item");

    public EntityType {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("An entity type is lower case, digits and dashes: '" + value + "'");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EntityType of(String value) {
        return new EntityType(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
