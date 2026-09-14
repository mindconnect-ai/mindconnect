package ai.mindconnect.llm.adapter.openai;

/**
 * Separates inline reasoning from answer text in a Chat Completions stream.
 *
 * <p>Some OpenAI-compatible servers hand a reasoning model's thoughts over in
 * the {@code content} field, wrapped in {@code <think>…</think>} (LM Studio
 * with reasoning parsing off, Together, Fireworks, Groq in {@code raw}
 * format). Left alone, the reasoning would land in the chat bubble as if it
 * were the answer. This splitter sits between the delta parser and the
 * stream handler and routes what is inside the tags to the thinking channel
 * and what is outside to the text channel.
 *
 * <p>Deltas are small and cut anywhere, so a tag can straddle two of them
 * ({@code "<thi"} + {@code "nk>"}). The splitter keeps back the shortest
 * tail that could still turn out to be the start of the tag it is waiting
 * for and releases it as soon as the next delta shows it was not. One
 * instance per stream; {@link #flush()} at the end releases whatever was
 * held back.
 */
final class ThinkTagSplitter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    /** What one delta turned into; either part may be {@code null}. */
    record Split(String text, String thinking) {
        static final Split NONE = new Split(null, null);
    }

    private boolean inside;
    private final StringBuilder held = new StringBuilder();

    /** Feeds the next content delta; returns the parts released by it. */
    Split feed(String delta) {
        if (delta == null || delta.isEmpty()) return Split.NONE;
        held.append(delta);
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        while (true) {
            String tag = inside ? CLOSE : OPEN;
            StringBuilder out = inside ? thinking : text;
            int at = held.indexOf(tag);
            if (at >= 0) {
                out.append(held, 0, at);
                held.delete(0, at + tag.length());
                inside = !inside;
                continue;
            }
            int keep = partialTagLength(tag);
            out.append(held, 0, held.length() - keep);
            held.delete(0, held.length() - keep);
            break;
        }
        return new Split(nullIfEmpty(text), nullIfEmpty(thinking));
    }

    /** End of stream: what was held back for a tag that never came. */
    Split flush() {
        if (held.isEmpty()) return Split.NONE;
        String rest = held.toString();
        held.setLength(0);
        return inside ? new Split(null, rest) : new Split(rest, null);
    }

    /** Length of the longest tail of {@code held} that is a proper prefix of {@code tag}. */
    private int partialTagLength(String tag) {
        int max = Math.min(tag.length() - 1, held.length());
        for (int len = max; len > 0; len--) {
            if (tag.startsWith(held.substring(held.length() - len))) return len;
        }
        return 0;
    }

    private static String nullIfEmpty(StringBuilder sb) {
        return sb.isEmpty() ? null : sb.toString();
    }
}
