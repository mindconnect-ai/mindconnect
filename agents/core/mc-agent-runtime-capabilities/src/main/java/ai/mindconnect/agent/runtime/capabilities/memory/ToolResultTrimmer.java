package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;

/**
 * The first and cheapest way to make a long history fit: old tool results
 * are what fills a context, and the model rarely needs them again. Starting
 * with the oldest, each is shown as a note until the history fits. The
 * message stays, so every tool call keeps its result and the model's API
 * stays satisfied.
 *
 * <p>A result shown as a note once is shown as that note from then on,
 * also when the history would fit again: the note is kept on the message
 * ({@link SessionStore#COMPRESSED}). A history whose beginning changes from
 * call to call costs the provider's prompt cache every time.
 */
final class ToolResultTrimmer {

    private final TokenCounter counter;
    private final int budget;
    private final int keepRecent;
    private final BiConsumer<TurnMessage, String> remember;

    /**
     * @param budget     the tokens the history may take
     * @param keepRecent how many of the newest tool results are never
     *                   touched: the model is still working with them
     * @param remember   told each result that is newly shown as a note, and
     *                   the note
     */
    ToolResultTrimmer(TokenCounter counter, int budget, int keepRecent, BiConsumer<TurnMessage, String> remember) {
        this.counter = counter;
        this.budget = budget;
        this.keepRecent = keepRecent;
        this.remember = remember;
    }

    List<TurnMessage> trim(List<TurnMessage> messages) {
        List<TurnMessage> trimmed = new ArrayList<>(messages);
        List<Integer> untouched = new ArrayList<>();
        for (int i = 0; i < trimmed.size(); i++) {
            TurnMessage message = trimmed.get(i);
            if (message.metadata().get(SessionStore.COMPRESSED) instanceof String note) {
                trimmed.set(i, shownAs(message, note));
            } else if (message.type() == TurnMessage.Type.TOOL_RESULT) {
                untouched.add(i);
            }
        }
        int total = counter.count(trimmed);
        for (int k = 0; k < untouched.size() - keepRecent && total > budget; k++) {
            TurnMessage message = trimmed.get(untouched.get(k));
            int tokens = counter.count(message.text());
            // With its id: the result is still in the session, and whoever may read it again asks by that.
            String note = "[Tool result removed to save context: " + tokens + " tokens"
                    + (message.id() == null ? "" : ", id=" + message.id()) + "]";
            int saved = tokens - counter.count(note);
            if (saved <= 0) {
                continue;
            }
            trimmed.set(untouched.get(k), shownAs(message, note));
            remember.accept(message, note);
            total -= saved;
        }
        return trimmed;
    }

    private static TurnMessage shownAs(TurnMessage message, String note) {
        return new TurnMessage(message.id(), message.turnId(), message.type(),
                List.of(new ContentPart.Text(note)), message.toolCalls(), message.metadata());
    }
}
