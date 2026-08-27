package ai.mindconnect.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${VAR_NAME}} and {@code ${VAR_NAME:default}} placeholders
 * in string values by looking them up in environment variables.
 * <p>
 * Rules:
 * <ul>
 *   <li>{@code ${MY_VAR}} — expands to the env var value; throws if absent.</li>
 *   <li>{@code ${MY_VAR:fallback}} — expands to the env var value, or
 *       {@code fallback} if the var is not set.</li>
 *   <li>A {@code null} input returns {@code null}.</li>
 *   <li>A value with no placeholders is returned unchanged.</li>
 *   <li>Multiple placeholders in a single value are all expanded.</li>
 * </ul>
 * Example JSON config value: {@code "${OPENAI_API_KEY}"} or
 * {@code "${LLM_BASE_URL:http://localhost:11434}"}.
 */
public final class EnvVarResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private EnvVarResolver() {}

    /**
     * True if {@code value} contains at least one {@code ${VAR}} /
     * {@code ${VAR:default}} placeholder. Use this to decide whether a value
     * should be treated as a deferred reference (resolved at use time) rather
     * than a literal — e.g. so a stored secret placeholder is not encrypted.
     */
    public static boolean containsPlaceholder(String value) {
        return value != null && PLACEHOLDER.matcher(value).find();
    }

    /**
     * Expands all {@code ${…}} placeholders in {@code value} using environment
     * variables. Returns {@code null} if {@code value} is {@code null}.
     *
     * @throws IllegalStateException if a placeholder has no default and the
     *                               environment variable is not set
     */
    public static String resolve(String value) {
        return resolve(value, System.getenv());
    }

    /**
     * Same as {@link #resolve(String)} but accepts an explicit env map — useful
     * for testing without touching real environment variables.
     */
    public static String resolve(String value, java.util.Map<String, String> env) {
        if (value == null) return null;
        Matcher m = PLACEHOLDER.matcher(value);
        if (!m.find()) return value; // fast path — no placeholders

        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            String varName = m.group(1);
            String fallback = m.group(2); // null if no ':default' was given
            String envVal = env.get(varName);
            if (envVal == null && fallback == null) {
                throw new IllegalStateException(
                        "Environment variable '" + varName + "' is not set and no default was provided");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(envVal != null ? envVal : fallback));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
