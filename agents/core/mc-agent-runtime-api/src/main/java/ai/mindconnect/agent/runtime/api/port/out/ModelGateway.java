package ai.mindconnect.agent.runtime.api.port.out;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/**
 * The way to a model. The gateway maps the request's model name to a
 * provider, its key and its endpoint; the runtime knows none of them.
 */
public interface ModelGateway {

    /**
     * Asks the model and blocks until the answer is complete.
     *
     * @param events where a streaming gateway reports the answer as it
     *               arrives; the complete answer is still what is returned
     */
    LlmAnswer ask(ModelRequest request, TurnEventListener events);

    /** Aborts the call of this turn that is in flight, from another thread. */
    void cancel(TurnId turnId);
}
