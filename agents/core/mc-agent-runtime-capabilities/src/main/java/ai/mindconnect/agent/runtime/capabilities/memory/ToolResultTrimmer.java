package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;

/**
 * The first and cheapest way to keep a long history small: old tool results
 * are what fills a context, and the model rarely needs them again. Such a
 * result is shown as a note. The message stays, so every tool call keeps
 * its result and the model's API stays satisfied.
 *
 * <p>A result shown as a note once is shown as that note from then on,
 * whatever the agent's pruning says later: the note is kept on the message
 * ({@link SessionStore#COMPRESSED}). A history whose beginning changes from
 * call to call costs the provider's prompt cache every time.
 *
 * <p>What the skill tool returned is never a note: it is instructions the
 * agent was told to follow, not a result it has dealt with.
 */
final class ToolResultTrimmer {

    /** After a turn, results are only made notes when that frees at least this much: less is not worth a changed history. */
    private static final int WORTH_PRUNING_TOKENS = 20_000;

    /** After a turn, the turn underway and the one before it are left alone: the model is still working with them. */
    private static final int TURNS_LEFT_ALONE = 2;

    private final TokenCounter counter;
    private final BiConsumer<TurnMessage, String> remember;

    /**
     * @param remember told each result that is newly shown as a note, and
     *                 the note
     */
    ToolResultTrimmer(TokenCounter counter, BiConsumer<TurnMessage, String> remember) {
        this.counter = counter;
        this.remember = remember;
    }

    /** The history with the notes it already has, and no new one. */
    List<TurnMessage> asNoted(List<TurnMessage> messages) {
        List<TurnMessage> noted = new ArrayList<>(messages);
        for (int i = 0; i < noted.size(); i++) {
            if (noted.get(i).metadata().get(SessionStore.COMPRESSED) instanceof String note) {
                noted.set(i, shownAs(noted.get(i), note));
            }
        }
        return noted;
    }

    /**
     * Starting with the oldest, results become notes until the history fits.
     *
     * @param budget     the tokens the history may take
     * @param keepRecent how many of the newest tool results are never
     *                   touched: the model is still working with them
     */
    List<TurnMessage> whenFull(List<TurnMessage> messages, int budget, int keepRecent) {
        List<TurnMessage> trimmed = asNoted(messages);
        List<Integer> prunable = prunable(trimmed, trimmed.size());
        int total = counter.count(trimmed);
        for (int k = 0; k < prunable.size() - keepRecent && total > budget; k++) {
            total -= note(trimmed, prunable.get(k));
        }
        return trimmed;
    }

    /**
     * Results of turns that are over become notes, whether or not the
     * history fits: going back from the turn before the one underway, the
     * newest {@code keepRecentTokens} of results stay, what is older goes.
     * What is decided depends only on turns that are over, so the history
     * does not change while a turn runs.
     */
    List<TurnMessage> afterTurn(List<TurnMessage> messages, int keepRecentTokens) {
        List<TurnMessage> trimmed = asNoted(messages);
        List<Integer> prunable = prunable(trimmed, startOfTurnsLeftAlone(trimmed));
        List<Integer> beyond = new ArrayList<>();
        int kept = 0;
        int freed = 0;
        for (int k = prunable.size() - 1; k >= 0; k--) {
            int tokens = counter.count(trimmed.get(prunable.get(k)).text());
            if (kept + tokens <= keepRecentTokens) {
                kept += tokens;
            } else {
                beyond.add(prunable.get(k));
                freed += tokens;
            }
        }
        if (freed >= WORTH_PRUNING_TOKENS) {
            beyond.forEach(index -> note(trimmed, index));
        }
        return trimmed;
    }

    /** Where the last {@link #TURNS_LEFT_ALONE} turns begin; 0 for a history with fewer. */
    private static int startOfTurnsLeftAlone(List<TurnMessage> messages) {
        int turns = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).type() == TurnMessage.Type.USER && ++turns == TURNS_LEFT_ALONE) {
                return i;
            }
        }
        return 0;
    }

    /** The tool results before {@code end} that may become a note, oldest first. */
    private static List<Integer> prunable(List<TurnMessage> messages, int end) {
        List<Integer> prunable = new ArrayList<>();
        for (int i = 0; i < end; i++) {
            TurnMessage message = messages.get(i);
            if (message.type() == TurnMessage.Type.TOOL_RESULT
                    && !(message.metadata().get(SessionStore.COMPRESSED) instanceof String)
                    && !SkillPlugin.TOOL.equals(message.metadata().get("toolName"))) {
                prunable.add(i);
            }
        }
        return prunable;
    }

    /** Shows the result as a note, if that saves anything; the tokens saved. */
    private int note(List<TurnMessage> messages, int index) {
        TurnMessage message = messages.get(index);
        int tokens = counter.count(message.text());
        // With its id: the result is still in the session, and whoever may read it again asks by that.
        String note = "[Tool result removed to save context: " + tokens + " tokens"
                + (message.id() == null ? "" : ", id=" + message.id()) + "]";
        int saved = tokens - counter.count(note);
        if (saved <= 0) {
            return 0;
        }
        messages.set(index, shownAs(message, note));
        remember.accept(message, note);
        return saved;
    }

    private static TurnMessage shownAs(TurnMessage message, String note) {
        return new TurnMessage(message.id(), message.turnId(), message.type(),
                List.of(new ContentPart.Text(note)), message.toolCalls(), message.metadata());
    }
}
