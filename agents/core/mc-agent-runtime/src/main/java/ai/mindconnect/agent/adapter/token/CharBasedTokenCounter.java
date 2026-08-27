package ai.mindconnect.agent.adapter.token;

import ai.mindconnect.agent.port.out.TokenCounter;

/**
 * Fallback token counter using character length heuristic.
 * Approximates ~3 chars per token (works reasonably for mixed-language text).
 */
public class CharBasedTokenCounter implements TokenCounter {

    private static final double CHARS_PER_TOKEN = 3.0;

    @Override
    public int countText(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
