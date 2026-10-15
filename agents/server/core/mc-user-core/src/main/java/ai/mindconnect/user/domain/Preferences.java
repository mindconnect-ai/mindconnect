package ai.mindconnect.user.domain;

import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What one screen or feature remembers for one user — the mailbox and folder
 * last open, the calendar view last chosen, a column last sorted by.
 *
 * <p>A document per scope rather than a row per value: a screen reads its
 * scope once when it opens and writes it when something changes, and the
 * scope is also what a feature clears when it forgets. The values are plain
 * strings — a preference is a small, flat thing, and a client that needs
 * structure writes JSON into one value.
 *
 * <p><b>Not a secret, not a setting for an agent.</b> Preferences are what a
 * user interface remembers. They are not encrypted, not handed to tools and
 * not shown as the user's variables; a value an agent or a tool should read
 * belongs in the user's variables instead.
 *
 * @param userId    whose they are
 * @param scope     who they belong to, e.g. {@code office.email} — see
 *                  {@link #SCOPE}
 * @param values    key to value, sorted by key
 * @param updatedAt when they were last written
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Preferences(UserId userId, String scope, Map<String, String> values, Instant updatedAt) {

    /** A scope: lower case, digits, dot, dash and underscore; starts with a letter or digit. */
    public static final java.util.regex.Pattern SCOPE = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** A key: letters, digits, dot, dash and underscore. */
    public static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public Preferences {
        Objects.requireNonNull(userId, "Preferences need a user");
        Objects.requireNonNull(scope, "Preferences need a scope");
        values = values == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(values));
    }

    /** Nothing remembered yet. */
    public static Preferences empty(UserId userId, String scope) {
        return new Preferences(userId, scope, Map.of(), null);
    }

    public String get(String key) {
        return values.get(key);
    }

    /** Not a property: a stored document says what it holds, not whether it holds anything. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return values.isEmpty();
    }
}
