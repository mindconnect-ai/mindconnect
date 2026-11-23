package ai.mindconnect.agent.runtime.api.hook;

import ai.mindconnect.agent.runtime.api.domain.ModelRequest;

/**
 * One call of the model.
 *
 * @param request what is sent. A hook that shortens the history changes it
 *                here and only here: the session keeps every message
 */
public record ModelCall(AgentContext context, ModelRequest request) {

    public ModelCall with(ModelRequest changed) {
        return new ModelCall(context, changed);
    }
}
