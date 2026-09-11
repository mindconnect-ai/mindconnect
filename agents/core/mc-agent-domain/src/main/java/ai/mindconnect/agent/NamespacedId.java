package ai.mindconnect.agent;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An id that knows which tenant it belongs to.
 *
 * <p>Every entity a tenant owns — agent, session, conversation, LLM config,
 * file, data pool, vector store, MCP server — is addressed by one of these
 * rather than by a bare string. Two reasons:
 *
 * <ul>
 *   <li>The namespace and the raw value always travel together. A port that
 *       takes {@code (Namespace, String id)} in one method and {@code (String
 *       id)} in the next has already lost the tenant once; a port that takes
 *       {@code AgentId} cannot. And a lookup by value alone, scanning every
 *       tenant for the first hit, is exactly the ambiguity that keeps two
 *       tenants from holding the same id.</li>
 *   <li>The same value may exist in several tenants. Seed data laid down for
 *       every tenant ({@code all/agents/web-researcher.json}) gets the same
 *       id in each of them, and a readable id such as {@code web-researcher}
 *       is a legitimate address — the value is a string, not a UUID.</li>
 * </ul>
 *
 * <p>One record per entity kind, each living in the module that owns the
 * entity ({@code LlmConfigId} in the LLM gateway, {@code FileId} in the file
 * store, {@code McpServerId} in the MCP gateway), all of the same shape, so
 * that {@code findById(AgentId)} refuses a {@code SessionId} at compile time.
 * There is no class hierarchy behind them: this interface exists for the few
 * generic places — directory layout, logging, a key column — that handle an
 * id without caring which kind it is. No port takes the interface.
 *
 * <p>The value is what a UUID guaranteed for free before: a safe file name
 * and path segment, so that {@code <namespace>/<store>/<value>.json} can be
 * written without escaping. Lower case only, because the developer's file
 * system treats {@code Foo} and {@code foo} as one file and Postgres does
 * not — a difference that would surface only in production. UUID strings
 * pass unchanged, which is why existing data needs no migration.
 *
 * <p>Not a wire format. REST paths carry the raw value and the tenant comes
 * from the request; a controller puts the two together. JSON on disk writes
 * the value and the namespace as two fields, as it did before.
 */
public interface NamespacedId {

    /** Lower-case letters, digits, {@code . _ -}; starts with a letter or digit; at most 128 characters. */
    Pattern VALUE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    Namespace namespace();

    String value();

    /** {@code <namespace>/<value>} — for logs and error messages, never for storage. */
    default String qualified() {
        return namespace().value() + "/" + value();
    }

    /** Whether this id lives in {@code namespace}. */
    default boolean in(Namespace namespace) {
        return namespace().equals(namespace);
    }

    /** The shared invariant every id record enforces in its constructor. */
    static void check(Namespace namespace, String value) {
        if (namespace == null) {
            throw new IllegalArgumentException("An id needs a namespace");
        }
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
