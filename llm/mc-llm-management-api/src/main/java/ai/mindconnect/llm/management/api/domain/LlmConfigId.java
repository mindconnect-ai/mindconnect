package ai.mindconnect.llm.management.api.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifies an LLM configuration. Lower-case letters, digits, {@code . _ -}; starts with a letter
 * or digit; at most 128 characters, as every id of mindconnect.
 */
public record LlmConfigId(String value) {

    public LlmConfigId {
        check(value);
    }

    public static LlmConfigId of(String value) {
        return new LlmConfigId(value);
    }

    /** A fresh, unique id. */
    public static LlmConfigId random() {
        return new LlmConfigId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }

    private static final Pattern VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private static void check(String value) {
        if (value == null || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Id value '" + value + "' is not a valid id: use lower-case letters, digits, "
                    + "'.', '_' or '-', start with a letter or digit, at most 128 characters");
        }
    }
}
