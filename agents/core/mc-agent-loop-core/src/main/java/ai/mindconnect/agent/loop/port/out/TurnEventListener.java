package ai.mindconnect.agent.loop.port.out;

import ai.mindconnect.agent.loop.domain.TurnStreamEvent;

/**
 * Where a turn's events go. The loop reports when the model is asked, every
 * stored message and the tool calls in it; it hands the same listener to the {@link LlmProvider},
 * which reports the fragments of the answer it is receiving.
 *
 * <p>Called on the thread that runs the turn, in the order things happen.
 * An implementation that throws costs the turn nothing: the event is lost,
 * the turn goes on.
 */
@FunctionalInterface
public interface TurnEventListener {

    void onEvent(TurnStreamEvent event);
}
