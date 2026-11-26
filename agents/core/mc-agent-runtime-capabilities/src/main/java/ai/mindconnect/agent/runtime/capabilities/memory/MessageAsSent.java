package ai.mindconnect.agent.runtime.capabilities.memory;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * How a capability hands a stored message to the model, when that is not as
 * it was said: with a note ahead of the user's text, say. Contributed by the
 * capability that changes the request of a model call, so that
 * {@link MemoryInspection} shows what the model reads without a model call
 * being made.
 */
@FunctionalInterface
public interface MessageAsSent {

    /** The message as the model reads it; the message itself when this capability adds nothing to it. */
    TurnMessage of(TurnMessage message, Session session);
}
