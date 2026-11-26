package ai.mindconnect.llm.adapter.openai;

/**
 * Routes what some OpenAI-compatible servers send inline as {@code <think>…</think>}
 * in the {@code content} field to the thinking channel instead of the chat bubble.
 * A thought is only looked for before the first real text, so a user quoting the
 * tag keeps their answer; a stray closing tag is dropped so a pre-filled template
 * does not leak it. Deltas cut anywhere, so the shortest tail that could still start
 * a tag is held back until the next delta decides; {@link #flush()} releases it.
 */
final class ThinkTagSplitter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    /** What one delta turned into; either part may be {@code null}. */
    record Split(String text, String thinking) {
        static final Split NONE = new Split(null, null);
    }

    private boolean inside;
    /** Whether non-blank answer text has gone out — after that, no thought can start. */
    private boolean textReleased;
    private final StringBuilder held = new StringBuilder();

    /** Feeds the next content delta; returns the parts released by it. */
    Split feed(String delta) {
        if (delta == null || delta.isEmpty()) return Split.NONE;
        held.append(delta);
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        while (true) {
            if (inside) {
                int at = held.indexOf(CLOSE);
                if (at >= 0) {
                    thinking.append(held, 0, at);
                    held.delete(0, at + CLOSE.length());
                    inside = false;
                    continue;
                }
                release(thinking, partialTagLength(CLOSE));
                break;
            }
            int close = held.indexOf(CLOSE);
            int open = textReleased ? -1 : held.indexOf(OPEN);
            if (open >= 0 && !held.substring(0, open).isBlank()) {
                // Real text came first in this very delta: the tag is quoted, not a thought.
                textReleased = true;
                open = -1;
            }
            if (open >= 0 && (close < 0 || open < close)) {
                text.append(held, 0, open);
                held.delete(0, open + OPEN.length());
                inside = true;
                continue;
            }
            if (close >= 0) {
                // A closing tag with no opening one: drop it, keep the text.
                text.append(held, 0, close);
                held.delete(0, close + CLOSE.length());
                continue;
            }
            release(text, Math.max(partialTagLength(CLOSE), textReleased ? 0 : partialTagLength(OPEN)));
            break;
        }
        if (!text.isEmpty() && !text.toString().isBlank()) textReleased = true;
        return new Split(nullIfEmpty(text), nullIfEmpty(thinking));
    }

    /** End of stream: what was held back for a tag that never came. */
    Split flush() {
        if (held.isEmpty()) return Split.NONE;
        String rest = held.toString();
        held.setLength(0);
        return inside ? new Split(null, rest) : new Split(rest, null);
    }

    /** Moves everything but the last {@code keep} chars of {@code held} to {@code out}. */
    private void release(StringBuilder out, int keep) {
        out.append(held, 0, held.length() - keep);
        held.delete(0, held.length() - keep);
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
