package ai.mindconnect.extension.domain;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.regex.Pattern;

/**
 * The id an extension gives itself in its manifest: lowercase, digits and
 * hyphens, two to 64 characters, starting with a letter or digit —
 * {@code acme-crm}. Global across every marketplace and every installation,
 * so a vendor prefix is the convention. In JSON it is its plain value, like
 * every other id.
 */
public record ExtensionId(@JsonValue String value) implements EntityId {

    private static final Pattern FORM = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");

    @JsonCreator
    public ExtensionId {
        if (value == null || !FORM.matcher(value).matches()) {
            throw new IllegalArgumentException("Extension id must match " + FORM + ": " + value);
        }
    }

    public static ExtensionId of(String value) {
        return new ExtensionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
