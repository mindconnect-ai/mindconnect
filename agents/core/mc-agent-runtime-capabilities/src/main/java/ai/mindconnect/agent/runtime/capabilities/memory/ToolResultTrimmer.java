package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.ArrayList;
import java.util.List;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;

/**
 * The first and cheapest way to make a long history fit: old tool results
 * are what fills a context, and the model rarely needs them again. Starting
 * with the oldest, each is replaced by a note until the history fits. The
 * message stays, so every tool call keeps its result and the model's API
 * stays satisfied.
 */
final class ToolResultTrimmer {

    private final TokenCounter counter;
    private final int budget;
    private final int keepRecent;

    /**
     * @param budget     the tokens the history may take
     * @param keepRecent how many of the newest tool results are never
     *                   touched: the model is still working with them
     */
    ToolResultTrimmer(TokenCounter counter, int budget, int keepRecent) {
        this.counter = counter;
        this.budget = budget;
        this.keepRecent = keepRecent;
    }

    List<TurnMessage> trim(List<TurnMessage> messages) {
        int total = counter.count(messages);
        if (total <= budget) {
            return messages;
        }
        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).type() == TurnMessage.Type.TOOL_RESULT) {
                results.add(i);
            }
        }
        List<TurnMessage> trimmed = new ArrayList<>(messages);
        for (int k = 0; k < results.size() - keepRecent && total > budget; k++) {
            TurnMessage message = trimmed.get(results.get(k));
            int tokens = counter.count(message.text());
            String note = "[Tool result removed to save context: " + tokens + " tokens]";
            int saved = tokens - counter.count(note);
            if (saved <= 0) {
                continue;
            }
            trimmed.set(results.get(k), new TurnMessage(message.id(), message.turnId(), message.type(),
                    List.of(new ContentPart.Text(note)), message.toolCalls(), message.metadata()));
            total -= saved;
        }
        return trimmed;
    }
}
