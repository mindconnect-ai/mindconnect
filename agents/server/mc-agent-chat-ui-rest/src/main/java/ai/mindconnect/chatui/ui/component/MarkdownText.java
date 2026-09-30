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
 * literally. Backticks pair across the lines of a paragraph, as marked pairs
 * them: a code span may run over a line break, and pairing line by line took
 * a stretch for code that marked renders as markdown. Where the escaper's
 * idea of a paragraph and marked's could still part — a code span over a
 * line break, or one with a {@code |} that a table would split into cells —
 * the span's {@code <} is escaped anyway: at worst it shows as {@code &lt;}.
 * Autolinks ({@code <https://…>}) keep working. Not recognised: the
 * indented (four-space) code block, which models rarely write and which cannot
 * be told from a nested list paragraph without parsing the whole document; a
 * {@code <} in one shows as {@code &lt;}.
 */
public final class MarkdownText {

    private MarkdownText() {}

    /**
     * An opening code fence as marked reads one: at most three spaces of indent, then three
     * or more backticks or tildes. A deeper indent is an indented code block to marked, not
     * a fence — taking it for one stopped the escaping while marked rendered the lines after
     * it as HTML.
     */
    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    /**
     * A line that starts a block of its own — an ATX heading, a block quote, a list item —
     * and so ends the paragraph before it: a code span does not run across it.
     */
    private static final Pattern BLOCK_START = Pattern.compile("^ {0,3}(#{1,6}(\\s|$)|>|[-+*](\\s|$)|\\d{1,9}[.)](\\s|$))");

    /** A line that is a block by itself — an ATX heading, a thematic break, a setext underline. */
    private static final Pattern WHOLE_LINE_BLOCK = Pattern.compile(
            "^ {0,3}(#{1,6}(\\s.*)?|([-*_])( *\\3){2,} *|=+ *|-+ *)$");

    /** The row under a table's header: cells of dashes, colons for alignment, split by {@code |}. */
    private static final Pattern TABLE_DELIMITER = Pattern.compile(
            "^ {0,3}\\|?( *:?-+:? *\\|)* *:?-+:? *\\|? *$");

    /** An autolink the renderer turns into a link — the one {@code <} that is not a tag. */
    private static final Pattern AUTOLINK = Pattern.compile("<(?:https?://|mailto:)[^\\s<>]*>");

    /** {@code markdown} with every tag-opening {@code <} outside code escaped; {@code null} stays {@code null}. */
    public static String safe(String markdown) {
        if (markdown == null || markdown.indexOf('<') < 0) return markdown;
        StringBuilder out = new StringBuilder(markdown.length() + 16);
        // The lines of the paragraph being read: escaped together, since a code span may span them.
        StringBuilder paragraph = new StringBuilder();
        // Inside a table every row is split into cells before a code span is looked for.
        boolean table = false;
        Pattern closing = null;
        int start = 0;
        while (start <= markdown.length()) {
            int end = markdown.indexOf('\n', start);
            boolean last = end < 0;
            String line = markdown.substring(start, last ? markdown.length() : end);
            // A CRLF line ends in \r, which no pattern's "." matches.
            String bare = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (closing == null) {
                Matcher m = FENCE_OPEN.matcher(bare);
                // A backtick fence's info string may not contain a backtick —
                // "```a``` b" on one line is inline code, not a fence.
                if (m.matches() && !(m.group(1).charAt(0) == '`' && m.group(2).indexOf('`') >= 0)) {
                    flush(paragraph, out);
                    table = false;
                    closing = closingFence(m.group(1));
                    out.append(line);
                    if (!last) out.append('\n');
                } else if (table && !bare.isBlank() && !BLOCK_START.matcher(bare).find()) {
                    out.append(escapeCells(line));
                    if (!last) out.append('\n');
                } else if (!paragraph.isEmpty() && bare.indexOf('|') >= 0 && TABLE_DELIMITER.matcher(bare).matches()) {
                    // The paragraph's last line was the header of a table: it is cells, not text.
                    String header = lastLine(paragraph);
                    flush(paragraph, out);
                    table = true;
                    out.append(escapeCells(header)).append(escapeCells(line));
                    if (!last) out.append('\n');
                } else {
                    table = false;
                    if (bare.isBlank() || BLOCK_START.matcher(bare).find()) {
                        flush(paragraph, out);
                    }
                    paragraph.append(line);
                    if (!last) paragraph.append('\n');
                    if (bare.isBlank() || WHOLE_LINE_BLOCK.matcher(bare).matches()) {
                        flush(paragraph, out);
                    }
                }
            } else {
                out.append(line);
                if (!last) out.append('\n');
                if (closing.matcher(bare).matches()) {
                    closing = null;
                }
            }
            if (last) break;
            start = end + 1;
        }
        flush(paragraph, out);
        return out.toString();
    }

    /** Escapes the paragraph read so far into {@code out} and starts the next one. */
    private static void flush(StringBuilder paragraph, StringBuilder out) {
        if (paragraph.isEmpty()) return;
        out.append(escapeInline(paragraph.toString()));
        paragraph.setLength(0);
    }

    /** Takes the last line, with its line break, off {@code paragraph}. */
    private static String lastLine(StringBuilder paragraph) {
        int from = paragraph.lastIndexOf("\n", paragraph.length() - 2) + 1;
        String line = paragraph.substring(from);
        paragraph.setLength(from);
        return line;
    }

    /**
     * A table row, escaped cell by cell: the table splits the row at every unescaped
     * {@code |} before it reads a code span, so a span never pairs across cells.
     */
    private static String escapeCells(String row) {
        StringBuilder out = new StringBuilder(row.length() + 8);
        int cell = 0;
        for (int i = 0; i < row.length(); i++) {
            if (row.charAt(i) == '|' && (i == 0 || row.charAt(i - 1) != '\\')) {
                out.append(escapeInline(row.substring(cell, i))).append('|');
                cell = i + 1;
            }
        }
        return out.append(escapeInline(row.substring(cell))).toString();
    }

    /**
     * What closes a fence opened with {@code fence}, by marked's rule: up to three spaces, the
     * opening run itself, any further backticks or tildes, then only spaces — so {@code ```~}
     * closes a {@code ```} fence, which a "same character, nothing else" rule missed and kept
     * the rest of the reply unescaped.
     */
    private static Pattern closingFence(String fence) {
        return Pattern.compile("^ {0,3}" + Pattern.quote(fence) + "[~`]* *$");
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

    /**
     * One paragraph outside a fence: code spans verbatim, a {@code <} elsewhere escaped unless
     * it opens an autolink. A code span over a line break or with an unescaped {@code |} has its
     * {@code <} escaped too — see the class comment.
     */
    private static String escapeInline(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '`') {
                // An escaped backtick is a plain character, not the start of a code
                // span — taking it for one left the tags "inside" it unescaped.
                out.append(text, i, i + 2);
                i += 2;
            } else if (c == '`') {
                int runEnd = i;
                while (runEnd < text.length() && text.charAt(runEnd) == '`') runEnd++;
                int close = closingRun(text, runEnd, runEnd - i);
                if (close >= 0) {
                    String span = text.substring(i, close);
                    out.append(uncertain(span) ? span.replace("<", "&lt;") : span);
                    i = close;
                } else {
                    out.append(text, i, runEnd);
                    i = runEnd;
                }
            } else if (c == '<') {
                Matcher link = AUTOLINK.matcher(text).region(i, text.length());
                if (link.lookingAt()) {
                    out.append(text, i, link.end());
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

    /**
     * Whether marked might not read {@code span} as one code span after all: it runs over a line
     * break, where a block the escaper does not know could end the paragraph, or it holds a
     * {@code |} that a table splits cells at, code span or not.
     */
    private static boolean uncertain(String span) {
        if (span.indexOf('\n') >= 0) return true;
        for (int i = span.indexOf('|'); i >= 0; i = span.indexOf('|', i + 1)) {
            if (i == 0 || span.charAt(i - 1) != '\\') return true;
        }
        return false;
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
