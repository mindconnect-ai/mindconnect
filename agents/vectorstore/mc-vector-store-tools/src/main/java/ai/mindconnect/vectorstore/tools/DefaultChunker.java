package ai.mindconnect.vectorstore.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Workflow-free default chunking, mirroring OpenAI's vector-store defaults:
 * chunks of at most 800 tokens with a 400-token overlap. Token counts are
 * approximated as {@code ceil(chars / 4)} — close enough for chunk sizing,
 * with no tokenizer dependency. Chunk boundaries snap back to the nearest
 * newline or sentence end where possible so chunks don't cut words mid-air.
 */
public final class DefaultChunker {

    /** OpenAI defaults: max_chunk_size_tokens 800, chunk_overlap_tokens 400. */
    public static final int DEFAULT_MAX_TOKENS = 800;
    public static final int DEFAULT_OVERLAP_TOKENS = 400;

    private static final int CHARS_PER_TOKEN = 4;

    private DefaultChunker() {}

    public static List<String> chunk(String text) {
        return chunk(text, DEFAULT_MAX_TOKENS, DEFAULT_OVERLAP_TOKENS);
    }

    /**
     * Splits {@code text} into overlapping chunks. {@code overlapTokens} must
     * be smaller than {@code maxTokens}; each next chunk starts
     * {@code maxTokens - overlapTokens} tokens after the previous one.
     */
    public static List<String> chunk(String text, int maxTokens, int overlapTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must be < maxTokens");
        }
        String normalised = text.strip();
        int maxChars = maxTokens * CHARS_PER_TOKEN;
        int stepChars = (maxTokens - overlapTokens) * CHARS_PER_TOKEN;

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalised.length()) {
            int hardEnd = Math.min(start + maxChars, normalised.length());
            int end = hardEnd == normalised.length() ? hardEnd : snapToBoundary(normalised, start, hardEnd);
            String piece = normalised.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end == normalised.length()) {
                break;
            }
            start += stepChars;
        }
        return chunks;
    }

    /**
     * Walks back from {@code hardEnd} (up to a quarter of the chunk) looking
     * for a newline, then a sentence end, then a space — the first found
     * boundary wins; otherwise the hard cut stands.
     */
    private static int snapToBoundary(String text, int start, int hardEnd) {
        int floor = Math.max(start + (hardEnd - start) * 3 / 4, start + 1);
        for (int i = hardEnd - 1; i >= floor; i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        for (int i = hardEnd - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        for (int i = hardEnd - 1; i >= floor; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return hardEnd;
    }
}
