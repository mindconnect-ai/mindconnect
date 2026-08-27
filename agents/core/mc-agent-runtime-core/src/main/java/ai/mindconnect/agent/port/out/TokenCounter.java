package ai.mindconnect.agent.port.out;

import ai.mindconnect.llm.domain.LlmMessage;

import java.util.List;

/**
 * Estimates the number of tokens in text or a list of LLM messages.
 * Implementations are model-specific (jtokkit for GPT-family, char-based fallback).
 */
public interface TokenCounter {

    /** Count tokens in a plain string. */
    int countText(String text);

    /** Count tokens across a list of messages (content + role overhead). */
    default int countMessages(List<LlmMessage> messages) {
        int total = 0;
        for (LlmMessage m : messages) {
            total += 4; // per-message overhead (role, separators)
            if (m.content() != null) total += countText(m.content());
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
