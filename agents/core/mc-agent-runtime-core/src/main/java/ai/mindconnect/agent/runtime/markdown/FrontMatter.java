package ai.mindconnect.agent.runtime.markdown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The YAML-ish header a Markdown file may open with, and the body after it:
 *
 * <pre>
 * ---
 * name: pdf-report
 * description: How this team formats a PDF report
 * ---
 * Body from here on.
 * </pre>
 *
 * <p>What the agent area writes and reads by hand — a project's sub-agents
 * ({@code .mindconnect/agents}), its skills ({@code .mindconnect/skills}) —
 * uses this one reader, so a file moved between them parses the same way.
 * It is deliberately not a YAML engine: scalars, flow lists
 * ({@code [a, b]}), block lists and comments, which is everything those
 * headers use, and no anchors, nesting or multi-line scalars.
 *
 * <p>A pair of {@code ---} lines with nothing in between is not a header but
 * a horizontal rule opening the text, and is left to the body.
 */
public final class FrontMatter {

    private FrontMatter() {}

    /**
     * Splits {@code content} into its header fields (keys lower-cased) and
     * the text after them. A file without a header parses as no fields and
     * the whole content as the body.
     */
    public static Parsed parse(String content) {
        if (content == null) return new Parsed(Map.of(), "");
        String text = content.stripLeading();
        if (!text.startsWith("---")) return new Parsed(Map.of(), content);
        int firstBreak = text.indexOf('\n');
        int close = firstBreak < 0 ? -1 : indexOfClosingFence(text, firstBreak + 1);
        if (close < 0) return new Parsed(Map.of(), content);
        Map<String, String> fields = fields(text.substring(firstBreak + 1, close));
        if (fields.isEmpty()) return new Parsed(Map.of(), content);
        int afterFence = text.indexOf('\n', close);
        return new Parsed(fields, afterFence < 0 ? "" : text.substring(afterFence + 1));
    }

    /**
     * The {@code key: value} lines of a header block, keys lower-cased.
     *
     * <p>A value may also arrive as an indented block sequence, which is what
     * anyone writing YAML reaches for:
     *
     * <pre>
     * tools:
     *   - file_read
     *   - grep
     * </pre>
     *
     * Those items are joined into the comma form, so the flow list and the
     * block list mean the same thing. They have to: reading the block form
     * as an empty value would turn a file naming two tools into one naming
     * none. For the same reason a blank line between the key and its items,
     * or between two items, does not end the list.
     */
    private static Map<String, String> fields(String block) {
        Map<String, String> head = new LinkedHashMap<>();
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                List<String> items = new ArrayList<>();
                for (int next = nextNonBlank(lines, i + 1);
                     next < lines.length && isSequenceItem(lines[next]);
                     next = nextNonBlank(lines, i + 1)) {
                    items.add(lines[next].stripLeading().substring(1).strip());
                    i = next;
                }
                value = String.join(", ", items);
            }
            head.put(key, unquote(value));
        }
        return head;
    }

    /** The index of the first line from {@code from} on that is not blank; {@code lines.length} when none is. */
    private static int nextNonBlank(String[] lines, int from) {
        int at = from;
        while (at < lines.length && lines[at].isBlank()) at++;
        return at;
    }

    /** A {@code - item} line of a block sequence, indented or not. */
    private static boolean isSequenceItem(String line) {
        String stripped = line.stripLeading();
        return stripped.length() > 1 && stripped.charAt(0) == '-'
                && Character.isWhitespace(stripped.charAt(1));
    }

    /** The index where a lone {@code ---} closes the header, or -1. */
    private static int indexOfClosingFence(String text, int from) {
        int at = from;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            String line = (end < 0 ? text.substring(at) : text.substring(at, end)).strip();
            if (line.equals("---")) return at;
            if (end < 0) return -1;
            at = end + 1;
        }
        return -1;
    }

    /** A quoted scalar without its quotes; anything else unchanged. */
    public static String unquote(String value) {
        if (value != null && value.length() > 1
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * A header list. {@code null} when the key is absent or has no value at
     * all — YAML's null, nothing said — and a list, possibly empty, when it
     * names one: {@code []} is an answer, and the answer is none.
     */
    public static List<String> listOf(String value) {
        if (value == null || value.isBlank()) return null;
        String inner = value.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        return Arrays.stream(inner.split(","))
                .map(String::strip)
                .map(FrontMatter::unquote)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** The header fields of one file and everything after them. */
    public record Parsed(Map<String, String> fields, String body) {

        public Parsed {
            fields = Map.copyOf(fields);
        }

        /** The field's value, or {@code fallback} when the header does not carry it. */
        public String get(String key, String fallback) {
            String value = fields.get(key);
            return value == null ? fallback : value;
        }

        /** The field as a list; see {@link FrontMatter#listOf(String)}. */
        public List<String> list(String key) {
            return listOf(fields.get(key));
        }
    }
}
