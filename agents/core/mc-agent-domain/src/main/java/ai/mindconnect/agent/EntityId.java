package ai.mindconnect.agent;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What every entity id shares: a string value that is a safe file name.
 *
 * <p>One record per entity kind — {@code AgentId}, {@code SessionId},
 * {@code LlmConfigId}, {@code FileId}, … — each living in the module that owns
 * the entity, all of the same shape, so that {@code findById(AgentId)} refuses a
 * {@code SessionId} at compile time. There is no class hierarchy behind them:
 * this interface exists for the few generic places that handle an id without
 * caring which kind it is.
 *
 * <p>The value is a safe file name and path segment, so that
 * {@code <store>/<value>.json} can be written without escaping. Lower case only,
 * because the developer's file system treats {@code Foo} and {@code foo} as one
 * file and Postgres does not — a difference that would surface only in
 * production. UUID strings pass unchanged, and a readable id such as
 * {@code web-researcher} is a legitimate address. In JSON an id is its value.
 *
 * <p>An id carries no namespace. A repository is bound to its namespace when it
 * is built; everything above the repositories works inside that one namespace
 * without naming it.
 */
public interface EntityId {

    /** Lower-case letters, digits, {@code . _ -}; starts with a letter or digit; at most 128 characters. */
    Pattern VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    String value();

    /** The invariant every id record enforces in its constructor. */
    static void check(String value) {
        if (value == null || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Id value '" + value + "' is not a valid id: use lower-case letters, digits, "
                    + "'.', '_' or '-', start with a letter or digit, at most 128 characters");
        }
    }

    /** A random value that satisfies {@link #VALUE}. */
    static String randomValue() {
        return UUID.randomUUID().toString();
    }
}
