package ai.mindconnect.agent.runtime.api.port.in;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.Turn;

/**
 * Who follows one turn: what the agent loop reports of it, and that it is
 * over. A loop that is cut off — the turn failed, a cancel closed the model
 * call — reports no end itself, so the end comes on its own.
 */
@FunctionalInterface
public interface TurnListener extends TurnEventListener {

    @Override
    void onEvent(TurnEvent event);

    /**
     * The turn is over — completed, incomplete, cancelled or failed. Called
     * once and last, with the turn as it ended.
     */
    default void onFinished(Turn turn) { }
}
