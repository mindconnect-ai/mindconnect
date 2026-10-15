package ai.mindconnect.agent.protocol.client;

import ai.mindconnect.agent.protocol.api.ToolDefinition;

import java.util.Map;
import java.util.function.Function;

/**
 * A client-side tool: definition plus the code that executes it. Used by
 * {@link ToolLoop} to answer {@code WAITING_FOR_TOOL_OUTPUT} responses —
 * against ANY backend, since the mechanic is pure protocol.
 */
public interface ToolHandler {

    ToolDefinition definition();

    /** Executes one call. Thrown exceptions become failed tool outputs the model can read. */
    String execute(Map<String, Object> arguments) throws Exception;

    static ToolHandler of(ToolDefinition definition, Function<Map<String, Object>, String> fn) {
        return new ToolHandler() {
            @Override public ToolDefinition definition() { return definition; }
            @Override public String execute(Map<String, Object> arguments) { return fn.apply(arguments); }
        };
    }
}
