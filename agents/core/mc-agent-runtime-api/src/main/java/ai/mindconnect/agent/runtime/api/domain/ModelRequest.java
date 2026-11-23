package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;

import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * One call to a model, complete: the gateway needs nothing else and looks
 * nothing up.
 *
 * @param settings the settings of this call, all layers already laid over
 *                 each other
 * @param messages the window the model sees — what of the conversation that
 *                 is, was decided before
 */
public record ModelRequest(
        TurnId turnId,
        ModelSettings settings,
        String instructions,
        List<TurnMessage> messages,
        List<ToolDefinition> tools
) {

    public ModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
