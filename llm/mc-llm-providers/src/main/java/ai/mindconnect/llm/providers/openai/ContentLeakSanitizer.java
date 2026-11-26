package ai.mindconnect.llm.adapter.openai;

import java.util.regex.Pattern;

/**
 * Drops Harmony-style channel markers and tool-call envelopes that a model
 * sometimes emits in the {@code content} stream instead of {@code tool_calls}. The
 * leak swallows the rest of the message, so nothing is reconstructed: the marker
 * chunk is dropped and the stream flows on. The patterns are anchored to short
 * token-shaped substrings because the gateway streams deltas, and a chunk that
 * merely contains a fragment, such as a user asking what the marker means, must
 * pass through untouched.
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
