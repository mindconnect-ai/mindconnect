package ai.mindconnect.agent.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a streamed answer into sentences, so speech can start while the agent
 * is still writing. A sentence ends at {@code . ! ? :} or {@code ;} followed by
 * white space, or at a line break. A full stop after a digit does not end one
 * — German ordinals ("am 24. November") would otherwise be spoken in halves.
 */
final class SentenceSplitter {

    private static final int MIN_LENGTH = 8;

    private final StringBuilder pending = new StringBuilder();

    /** Adds a fragment and returns the sentences it completed, in order. */
    List<String> add(String fragment) {
        pending.append(fragment);
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < pending.length() - 1; i++) {
            if (endsSentence(i) && pending.substring(start, i + 1).strip().length() >= MIN_LENGTH) {
                sentences.add(pending.substring(start, i + 1).strip());
                start = i + 1;
            }
        }
        pending.delete(0, start);
        return sentences;
    }

    /** What is left once the answer is complete, or an empty string. */
    String flush() {
        String rest = pending.toString().strip();
        pending.setLength(0);
        return rest;
    }

    private boolean endsSentence(int i) {
        char c = pending.charAt(i);
        if (c == '\n') return true;
        if (!Character.isWhitespace(pending.charAt(i + 1))) return false;
        if (c == '.') return i == 0 || !Character.isDigit(pending.charAt(i - 1));
        return c == '!' || c == '?' || c == ':' || c == ';';
    }
}
