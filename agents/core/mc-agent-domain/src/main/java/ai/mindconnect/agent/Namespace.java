package ai.mindconnect.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The one storage partition everything of the agents area lives in: a
 * directory name on disk, a column value in Postgres, a path segment in a
 * URL. In JSON it is its plain value, like every other id.
 */
public record Namespace(String value) {

    public static final Namespace DEFAULT = new Namespace("local");

    @JsonCreator
    public Namespace {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Namespace must not be blank");
        }
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
