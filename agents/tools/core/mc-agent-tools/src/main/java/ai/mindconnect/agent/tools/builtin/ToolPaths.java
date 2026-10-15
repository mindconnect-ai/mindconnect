package ai.mindconnect.agent.tools.builtin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for path-handling tools ({@code file_read}, {@code file_write},
 * {@code file_list}, {@code glob}) — accept the kinds of slightly-off arguments
 * that LLMs love to produce and turn them into something usable, or fail with
 * a message the LLM can self-correct from.
 *
 * <p>Two concerns live here:
 * <ol>
 *   <li><b>Path normalisation</b> — expand {@code ~} / {@code $HOME}, and
 *       relativise absolute paths that point inside {@code baseDir} so the
 *       sandbox check in the caller still passes.</li>
 *   <li><b>Argument resolution</b> — read the canonical key first, but fall
 *       back to common misspellings ({@code filename}, {@code file_path},
 *       {@code file}) before declaring the argument missing. The fallback is
 *       reported back so the LLM sees the corrected name.</li>
 * </ol>
 */
public final class ToolPaths {

    /** Common LLM aliases for the {@code path} argument. */
    public static final List<String> PATH_ALIASES =
            List.of("path", "filename", "file_path", "file", "filepath", "name");

    /** Common LLM aliases for the {@code content} argument. */
    public static final List<String> CONTENT_ALIASES =
            List.of("content", "text", "body", "data");

    private ToolPaths() {}

    /**
     * Picks the first matching key from {@code aliases} present in
     * {@code arguments} with a non-blank string value. Returns {@code null}
     * if none of the aliases match.
     */
    public static String firstString(Map<String, Object> arguments, List<String> aliases) {
        for (String key : aliases) {
            Object v = arguments.get(key);
            if (v != null) {
                String s = v.toString();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    /**
     * Normalises an LLM-provided path string for use against {@code baseDir}:
     * <ul>
     *   <li>{@code ~} and {@code ~/...} expand to {@code System.getProperty("user.home")}</li>
     *   <li>{@code $HOME} or {@code ${HOME}} expands to the same</li>
     *   <li>Absolute paths inside {@code baseDir} are made relative to {@code baseDir}
     *       so the caller's {@code baseDir.resolve(...)} still works correctly</li>
     *   <li>Absolute paths outside {@code baseDir} are returned as-is — the
     *       caller's sandbox check will reject them with a clean error</li>
     * </ul>
     */
    public static String normalise(String raw, Path baseDir) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            if (s.equals("~")) {
                s = home;
            } else if (s.startsWith("~/")) {
                s = home + s.substring(1);
            } else if (s.startsWith("$HOME")) {
                s = home + s.substring("$HOME".length());
            } else if (s.startsWith("${HOME}")) {
                s = home + s.substring("${HOME}".length());
            }
        }

        // If it's an absolute path inside baseDir, relativise so the caller's
        // baseDir.resolve(...) stays in-bounds (resolve drops the left operand
        // when the right is absolute, which would bypass the sandbox check).
        Path asPath = Path.of(s);
        if (asPath.isAbsolute()) {
            Path normalised = asPath.normalize();
            if (normalised.startsWith(baseDir)) {
                Path relative = baseDir.relativize(normalised);
                return relative.toString().isEmpty() ? "." : relative.toString();
            }
        }
        return s;
    }

    /**
     * Builds a self-correcting error string for the LLM when a required
     * argument is missing — lists the canonical name and the keys it actually
     * sent, so the next attempt has a chance to fix itself.
     */
    public static String missingArgError(String canonicalName,
                                          List<String> aliases,
                                          Map<String, Object> arguments) {
        return "Error: '" + canonicalName + "' is required (also accepted: "
                + String.join(", ", aliases.subList(1, aliases.size()))
                + "). Received keys: " + arguments.keySet();
    }
}
