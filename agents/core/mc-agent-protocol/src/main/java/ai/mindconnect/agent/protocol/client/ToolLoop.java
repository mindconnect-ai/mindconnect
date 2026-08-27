package ai.mindconnect.agent.protocol.client;

import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.api.AgentResponses;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.ToolDefinition;
import ai.mindconnect.agent.protocol.item.ConversationItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side tool executor: drives the {@code WAITING_FOR_TOOL_OUTPUT}
 * cycle until the response is terminal. Declares its handlers' definitions
 * as client tools, executes every open {@code FunctionCall} locally and
 * feeds the outputs back as the next request's input.
 *
 * <p>Backend-agnostic on purpose — the same loop runs against the
 * MindConnect runtime or the OpenAI backend, because the interruption
 * mechanic is part of the protocol, not of any implementation.
 */
public final class ToolLoop {

    private final AgentResponses responses;
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final int maxRounds;

    public ToolLoop(AgentResponses responses, List<ToolHandler> handlers) {
        this(responses, handlers, 10);
    }

    public ToolLoop(AgentResponses responses, List<ToolHandler> handlers, int maxRounds) {
        this.responses = responses;
        handlers.forEach(h -> this.handlers.put(h.definition().name(), h));
        this.maxRounds = maxRounds;
    }

    /** Runs the request, answering tool calls locally until the response is terminal. */
    public Response run(ResponseRequest request) {
        List<ToolDefinition> tools = mergedTools(request);
        Response response = responses.create(request.withClientTools(tools));

        for (int round = 0; round < maxRounds && waitsForTools(response); round++) {
            List<ConversationItem> outputs = new ArrayList<>();
            for (ConversationItem.FunctionCall call : response.openFunctionCalls()) {
                outputs.add(execute(call));
            }
            if (outputs.isEmpty()) break;   // waiting, but nothing we can answer
            response = responses.create(
                    new ResponseRequest(request.sessionId(), outputs, false, tools));
        }
        return response;
    }

    private static boolean waitsForTools(Response response) {
        return response.status() == ResponseStatus.INCOMPLETE
                && response.incompleteReason() == IncompleteReason.WAITING_FOR_TOOL_OUTPUT;
    }

    private ConversationItem execute(ConversationItem.FunctionCall call) {
        ToolHandler handler = handlers.get(call.name());
        if (handler == null) {
            return new ConversationItem.FunctionCallOutput(call.callId(),
                    "Error: no local handler for tool '" + call.name() + "'", true);
        }
        try {
            return new ConversationItem.FunctionCallOutput(call.callId(), handler.execute(call.arguments()), false);
        } catch (Exception e) {
            return new ConversationItem.FunctionCallOutput(call.callId(), "Error: " + e.getMessage(), true);
        }
    }

    /** Request-supplied client tools win over handler definitions of the same name. */
    private List<ToolDefinition> mergedTools(ResponseRequest request) {
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        request.clientTools().forEach(t -> byName.put(t.name(), t));
        handlers.values().forEach(h -> byName.putIfAbsent(h.definition().name(), h.definition()));
        return List.copyOf(byName.values());
    }
}
