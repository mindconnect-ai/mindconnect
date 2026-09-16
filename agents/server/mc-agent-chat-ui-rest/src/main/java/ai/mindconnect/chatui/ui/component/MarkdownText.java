package ai.mindconnect.chatui.ui.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown the chat did not write itself — an agent's answer, its thoughts, a
 * tool's output, what the user typed — made safe to hand to the markdown
 * renderer.
 *
 * <p>The renderer passes raw HTML straight through, which is right for copy a
 * page author wrote and wrong for everything a conversation carries. A reply
 * that quotes {@code <div className="card">} outside a code block became a real,
 * unclosed {@code <div>} that swallowed the rest of the conversation and broke
 * the layout; a reply with {@code <img onerror=…>} would run script. So every
 * {@code <} that could open a tag is written as {@code &lt;}: the reader sees
 * the text exactly as it was sent, and the DOM gets no element from it.
 *
 * <p>Code is left alone — inside a fenced block or an inline code span the
 * renderer escapes by itself, and an {@code &lt;} there would show up
 * literally. Autolinks ({@code <https://…>}) keep working. Not recognised: the
 * indented (four-space) code block, which models rarely write and which cannot
 * be told from a nested list paragraph without parsing the whole document; a
 * {@code <} in one shows as {@code &lt;}.
 */
public final class MarkdownText {

    private MarkdownText() {}

    /** An opening or closing code fence: three or more backticks or tildes, at any indent. */
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");

    /** An autolink the renderer turns into a link — the one {@code <} that is not a tag. */
    private static final Pattern AUTOLINK = Pattern.compile("<(?:https?://|mailto:)[^\\s<>]*>");

    /** {@code markdown} with every tag-opening {@code <} outside code escaped; {@code null} stays {@code null}. */
    public static String safe(String markdown) {
        if (markdown == null || markdown.indexOf('<') < 0) return markdown;
        StringBuilder out = new StringBuilder(markdown.length() + 16);
        String fence = null;
        int start = 0;
        while (start <= markdown.length()) {
            int end = markdown.indexOf('\n', start);
            boolean last = end < 0;
            String line = markdown.substring(start, last ? markdown.length() : end);
            Matcher m = FENCE.matcher(line);
            if (fence == null) {
                // A backtick fence's info string may not contain a backtick —
                // "```a``` b" on one line is inline code, not a fence.
                if (m.matches() && !(m.group(1).charAt(0) == '`' && m.group(2).indexOf('`') >= 0)) {
                    fence = m.group(1);
                    out.append(line);
                } else {
                    out.append(escapeInline(line));
                }
            } else {
                out.append(line);
                if (m.matches() && m.group(2).isBlank()
                        && m.group(1).charAt(0) == fence.charAt(0)
                        && m.group(1).length() >= fence.length()) {
                    fence = null;
                }
            }
            if (last) break;
            out.append('\n');
            start = end + 1;
        }
        return out.toString();
    }

    /**
     * {@code text} as one fenced code block that nothing inside it can close:
     * the fence is one backtick longer than the longest run in the text.
     */
    public static String fenced(String text) {
        return fenced("", text);
    }

    /** The same, with an info string ({@code json}, {@code text}) after the opening fence. */
    public static String fenced(String info, String text) {
        String body = text == null ? "" : text;
        int longest = 0;
        int run = 0;
        for (int i = 0; i < body.length(); i++) {
            run = body.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + (info == null ? "" : info) + "\n" + body + (body.endsWith("\n") ? "" : "\n") + fence;
    }

    /** One line outside a fence: code spans verbatim, a {@code <} elsewhere escaped unless it opens an autolink. */
    private static String escapeInline(String line) {
        StringBuilder out = new StringBuilder(line.length() + 8);
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '`') {
                int runEnd = i;
                while (runEnd < line.length() && line.charAt(runEnd) == '`') runEnd++;
                int close = closingRun(line, runEnd, runEnd - i);
                if (close >= 0) {
                    out.append(line, i, close);
                    i = close;
                } else {
                    out.append(line, i, runEnd);
                    i = runEnd;
                }
            } else if (c == '<') {
                Matcher link = AUTOLINK.matcher(line).region(i, line.length());
                if (link.lookingAt()) {
                    out.append(line, i, link.end());
                    i = link.end();
                } else {
                    out.append("&lt;");
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The index just past a run of exactly {@code length} backticks at or after {@code from}, or -1. */
    private static int closingRun(String line, int from, int length) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) != '`') {
                i++;
                continue;
            }
            int runEnd = i;
            while (runEnd < line.length() && line.charAt(runEnd) == '`') runEnd++;
            if (runEnd - i == length) return runEnd;
            i = runEnd;
        }
        return -1;
    }
}
