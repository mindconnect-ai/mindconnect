package ai.mindconnect.llm.adapter.openai;

import java.util.regex.Pattern;

/**
 * Removes Harmony-style channel markers and stray tool-call envelopes that
 * a model sometimes emits in the {@code content} stream instead of through
 * the proper {@code tool_calls} field.
 *
 * <p>Symptoms in the wild:
 * <ul>
 *   <li>{@code &lt;|start|&gt;assistant&lt;|channel|&gt;commentary to=functions.web_read &lt;|constrain|&gt;json&lt;|message|&gt;…}</li>
 *   <li>{@code to=functions.todo_write 开号网址json {"todos":[…]}}</li>
 *   <li>Bare {@code <|im_start|>} / {@code <|im_end|>} pairs from the gpt-oss family</li>
 * </ul>
 *
 * <p>The leak typically swallows the rest of the message, so we don't try
 * to reconstruct anything — we just drop the marker chunk and let the rest
 * of the stream flow. When the model emits a clean message, every pattern
 * misses and {@link #sanitize} returns the input verbatim.
 *
 * <p>Operates per-chunk because the gateway streams deltas. The patterns
 * are anchored to short token-shaped substrings so a partial chunk that
 * just happens to contain a fragment isn't false-positively stripped
 * (e.g. the user asking literally "what does {@code &lt;|start|&gt;} mean?").
 */
public final class ContentLeakSanitizer {

    private ContentLeakSanitizer() {}

    /** Token-like channel markers: {@code <|start|>}, {@code <|channel|>}, etc. */
    private static final Pattern CHANNEL_MARKER = Pattern.compile(
            "<\\|(?:start|end|im_start|im_end|channel|message|constrain|return)\\|>");

    /** Harmony "to=functions.<name>" prefix that announces a tool call. */
    private static final Pattern FUNCTIONS_CALL = Pattern.compile(
            "(?:^|\\s)to=functions\\.[A-Za-z_][A-Za-z0-9_]*\\b");

    /**
     * Drops known leak markers from {@code text}. Returns the cleaned
     * string; an all-marker chunk collapses to the empty string and the
     * caller can treat that as a no-op delta.
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) return text;
        String stripped = CHANNEL_MARKER.matcher(text).replaceAll("");
        stripped = FUNCTIONS_CALL.matcher(stripped).replaceAll("");
        return stripped;
    }
}
