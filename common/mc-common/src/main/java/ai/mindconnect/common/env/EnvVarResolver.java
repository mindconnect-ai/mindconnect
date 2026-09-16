package ai.mindconnect.common.env;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a {@code ${VAR}} placeholder gets its value from, and the expansion
 * of such placeholders in a string.
 *
 * <p>The process environment is one such source — the only one a library or
 * a desktop app needs. A server that several people share has more: what a
 * user stored for themselves, what a namespace stores for everyone working in
 * it. {@link #chain(EnvVarResolver...)} asks them in order and the first one
 * that knows the name answers, so a user's own API key wins over the
 * namespace's, and the namespace's over the one the process was started with.
 *
 * <p>A source can be {@linkplain #personal() personal}: it holds what one
 * user brought for themselves. Personal values are for secrets — an API key —
 * and a config's other fields resolve from {@link #shared()}, the same chain
 * without the personal sources, so nobody redirects a shared config's endpoint
 * to a host of their own while the installation's key still travels with it.
 *
 * <p>Placeholders: {@code ${MY_VAR}} expands to the variable's value and
 * throws if no source has it; {@code ${MY_VAR:fallback}} takes the fallback
 * instead. Several placeholders in one value are all expanded; a value
 * without any is returned unchanged; {@code null} stays {@code null}.
 *
 * <p>Implementations must be safe to call from any thread; a source that
 * depends on who is asking reads that from the current scope on every call.
 */
public interface EnvVarResolver {

    /** The shape of a variable name: a letter or underscore, then letters, digits and underscores — {@code OPENAI_API_KEY}. */
    Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    /** The value of {@code name}, empty when this source does not have it. */
    Optional<String> get(String name);

    /**
     * Every variable this source has, as a map — for a consumer that enumerates rather
     * than asks by name, such as the workflow engine's {@code env} variable. In a chain the
     * first source that has a name wins here too. A fresh map per call; edits do not
     * write back.
     */
    Map<String, String> asMap();

    /** True when this source holds what one user brought for themselves; see {@link #shared()}. */
    default boolean personal() {
        return false;
    }

    /**
     * This resolver without its personal sources — what a config's non-secret
     * fields (a model, an endpoint) resolve from. A source that is not
     * personal is its own shared view; a personal one has none.
     */
    default EnvVarResolver shared() {
        return personal() ? none() : this;
    }

    /**
     * Expands all {@code ${…}} placeholders in {@code value} from this resolver.
     *
     * @throws IllegalStateException if a placeholder has no default and no source has the variable
     */
    default String resolve(String value) {
        if (value == null) return null;
        Matcher m = PLACEHOLDER.matcher(value);
        if (!m.find()) return value; // fast path — no placeholders

        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            String varName = m.group(1);
            String fallback = m.group(2); // null if no ':default' was given
            String envVal = get(varName).orElse(null);
            if (envVal == null && fallback == null) {
                throw new IllegalStateException(
                        "Environment variable '" + varName + "' is not set and no default was provided");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(envVal != null ? envVal : fallback));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * This resolver with every answer remembered — for one unit of work that
     * asks for the same names several times, such as resolving the fields of
     * one config, so a source backed by a store is read once per name.
     */
    default EnvVarResolver memoized() {
        return new MemoizedEnvVarResolver(this);
    }

    /**
     * True if {@code value} contains at least one {@code ${VAR}} /
     * {@code ${VAR:default}} placeholder. Use this to decide whether a value
     * should be treated as a deferred reference (resolved at use time) rather
     * than a literal — e.g. so a stored secret placeholder is not encrypted.
     */
    static boolean containsPlaceholder(String value) {
        return value != null && PLACEHOLDER.matcher(value).find();
    }

    /** True when {@code name} has the shape of a variable name ({@link #NAME}). */
    static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * Rejects a map that could not be stored as somebody's variables: a name that
     * is not a {@link #NAME}, or a value that is null or blank.
     *
     * @throws IllegalArgumentException naming the offending entry
     */
    static void requireValid(Map<String, String> vars) {
        if (vars == null) return;
        vars.forEach((name, value) -> requireValid(name, value));
    }

    /** {@link #requireValid(Map)} for one entry. */
    static void requireValid(String name, String value) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a variable name: letters, digits and '_', not starting with a digit");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Variable '" + name + "' has no value");
        }
    }

    /** The process environment — {@link System#getenv()}. */
    static EnvVarResolver system() {
        return SystemEnvVarResolver.INSTANCE;
    }

    /** A fixed set of variables — tests, and hosts that assemble the environment themselves. */
    static EnvVarResolver of(Map<String, String> vars) {
        return new MapEnvVarResolver(vars);
    }

    /** No variables at all. */
    static EnvVarResolver none() {
        return MapEnvVarResolver.EMPTY;
    }

    /** {@code sources} in order; the first one that has the name answers. */
    static EnvVarResolver chain(EnvVarResolver... sources) {
        return new ChainedEnvVarResolver(List.of(sources));
    }

    /** {@link #chain(EnvVarResolver...)} from a list. */
    static EnvVarResolver chain(List<EnvVarResolver> sources) {
        return new ChainedEnvVarResolver(sources);
    }
}
