package ai.mindconnect.agent.runtime.api.port.out;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import java.util.OptionalInt;

import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
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

    /**
     * How many tokens the model these settings name can read at once; empty
     * when the gateway does not know. Memory sizes what it sends by it.
     */
    default OptionalInt contextWindowTokens(ModelSettings settings) {
        return OptionalInt.empty();
    }

    /** Aborts the call of this turn that is in flight, from another thread. */
    void cancel(TurnId turnId);
}
