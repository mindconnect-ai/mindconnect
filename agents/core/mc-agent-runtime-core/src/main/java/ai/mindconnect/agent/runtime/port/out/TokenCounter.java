package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;

import java.util.List;

/**
 * Estimates the number of tokens in text or a list of LLM messages.
 * Implementations are model-specific (jtokkit for GPT-family, char-based fallback).
 */
public interface TokenCounter {

    /**
     * What an image costs, whatever its pixels: the order of magnitude of a
     * full-detail picture at OpenAI and Anthropic (a 1-megapixel image is
     * about 1,500 tokens at either). Media has no text to count, so the
     * budget takes a flat estimate rather than nothing.
     */
    int IMAGE_TOKENS = 1_600;

    /** A document's estimate: one token per this many bytes of the file, never fewer than {@link #DOCUMENT_MIN_TOKENS}. */
    int DOCUMENT_BYTES_PER_TOKEN = 100;

    /** The least a document is taken to cost — one text-heavy PDF page. */
    int DOCUMENT_MIN_TOKENS = 1_000;

    /** Count tokens in a plain string. */
    int countText(String text);

    /**
     * The estimate for one content block: text is counted, an image is {@link #IMAGE_TOKENS}; a
     * document is sized by its bytes — a PDF page of text runs to a few
     * thousand tokens, and the base64 length is what is known here.
     */
    default int countPart(LlmContent part) {
        return switch (part) {
            case LlmContent.Text t -> countText(t.text());
            case LlmContent.Image i -> IMAGE_TOKENS;
            case LlmContent.Document d -> Math.max(DOCUMENT_MIN_TOKENS,
                    decodedLength(d.base64()) / DOCUMENT_BYTES_PER_TOKEN);
        };
    }

    /** The byte count a base64 string decodes to, without decoding it. */
    private static int decodedLength(String base64) {
        if (base64 == null || base64.isEmpty()) return 0;
        int padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
        return (int) Math.max(0, (long) base64.length() * 3 / 4 - padding);
    }

    /** Count tokens across a list of messages (content blocks + role overhead). */
    default int countMessages(List<LlmMessage> messages) {
        int total = 0;
        for (LlmMessage m : messages) {
            total += 4; // per-message overhead (role, separators)
            for (LlmContent part : m.parts()) total += countPart(part);
            if (m.toolCalls() != null) {
                for (var tc : m.toolCalls()) {
                    total += countText(tc.name());
                    total += countText(tc.arguments() != null ? tc.arguments().toString() : "");
                }
            }
        }
        return total;
    }
}
