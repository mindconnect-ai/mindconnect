package ai.mindconnect.mcp.gateway.admin.ui;

import java.util.List;
import java.util.Locale;

/**
 * Suggests the prefix a registration should carry, from its catalog id and
 * the names its tools already have.
 *
 * <p>The naive answer — take the id — stutters: the catalog entry
 * {@code openbnb-airbnb} offers {@code airbnb_search}, and the two together
 * make {@code openbnb_airbnb_airbnb_search}. Server authors often prefix
 * their own tools already, and a catalog id often ends in the same word.
 *
 * <p>So: if the id ends where the tools begin, drop the overlap. Never drop
 * everything — a prefix has to stay, or two servers of the same kind collide.
 */
final class ToolNamePrefixes {

    private ToolNamePrefixes() {
    }

    /**
     * @param entryId    the catalog's id, e.g. {@code openbnb-airbnb}
     * @param toolNames  what the server offers, e.g. {@code airbnb_search}
     */
    static String suggest(String entryId, List<String> toolNames) {
        String base = normalise(entryId);
        String shared = sharedFirstSegment(toolNames);
        if (shared == null || base.isEmpty()) {
            return base;
        }
        // "openbnb_airbnb" + tools starting "airbnb_" → "openbnb"
        String suffix = "_" + shared;
        if (base.endsWith(suffix)) {
            return base.substring(0, base.length() - suffix.length());
        }
        // The id *is* the tools' prefix ("airbnb" + airbnb_search). Keeping it
        // doubles the word, dropping it leaves nothing to tell two servers
        // apart — the double reads worse, so keep the id.
        return base;
    }

    /** Lowercase, everything outside [a-z0-9] to underscores, trimmed. */
    static String normalise(String value) {
        String cleaned = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return cleaned.replaceAll("^_+|_+$", "");
    }

    /**
     * The first name segment all tools share, or null. Needs at least two
     * tools: one tool called {@code search} says nothing about a convention.
     */
    private static String sharedFirstSegment(List<String> toolNames) {
        if (toolNames == null || toolNames.size() < 2) {
            return null;
        }
        String candidate = null;
        for (String name : toolNames) {
            int underscore = name == null ? -1 : name.indexOf('_');
            if (underscore <= 0) {
                return null;                     // a name without a segment breaks the rule
            }
            String segment = name.substring(0, underscore).toLowerCase(Locale.ROOT);
            if (candidate == null) {
                candidate = segment;
            } else if (!candidate.equals(segment)) {
                return null;
            }
        }
        return candidate;
    }
}
