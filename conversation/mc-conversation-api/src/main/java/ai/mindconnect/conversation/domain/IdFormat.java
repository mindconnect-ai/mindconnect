package ai.mindconnect.conversation.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The value every id of a conversation shares: a safe file name and path
 * segment, lower case so that a file system and Postgres agree on it. A UUID
 * passes unchanged.
 */
final class IdFormat {

    private static final Pattern VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private IdFormat() {
    }

    static String checked(String value) {
        if (value == null || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Id value '" + value + "' is not a valid id: use lower-case letters, "
                    + "digits, '.', '_' or '-', start with a letter or digit, at most 128 characters");
        }
        return value;
    }

    static String random() {
        return UUID.randomUUID().toString();
    }
}
