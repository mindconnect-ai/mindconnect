package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.Turn;

/**
 * Who hears a chat's turn: what the runtime reports of it, of the turns of
 * the sub-agents under it, and what it has to ask before it can go on.
 */
public interface ChatTurnListener {

    /** An event of the chat's own turn. */
    void onEvent(TurnEvent event);

    /**
     * An event of a sub-agent's turn.
     *
     * @param subAgent the sub-agent's turn as it was when its first event came: its id, agent and session
     */
    void onSubAgentEvent(Turn subAgent, TurnEvent event);

    /** The turn waits for these to be answered. */
    void asked(List<Interruption> interruptions);

    /** A listener that hears nothing: for a caller that only waits for the answer. */
    static ChatTurnListener none() {
        return of(event -> { }, (subAgent, event) -> { }, interruptions -> { });
    }

    /** A listener made of one function per call. */
    static ChatTurnListener of(Consumer<TurnEvent> onEvent, BiConsumer<Turn, TurnEvent> onSubAgentEvent,
                               Consumer<List<Interruption>> asked) {
        return new ChatTurnListener() {
            @Override
            public void onEvent(TurnEvent event) {
                onEvent.accept(event);
            }

            @Override
            public void onSubAgentEvent(Turn subAgent, TurnEvent event) {
                onSubAgentEvent.accept(subAgent, event);
            }

            @Override
            public void asked(List<Interruption> interruptions) {
                asked.accept(interruptions);
            }
        };
    }
}
