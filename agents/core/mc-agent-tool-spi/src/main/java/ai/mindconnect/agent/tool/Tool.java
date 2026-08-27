package ai.mindconnect.agent.tool;

import java.util.Map;

public interface Tool {
    String name();
    String description();
    Map<String, Object> parametersSchema();
    String execute(Map<String, Object> arguments);

    /** If true, the tool result is streamed directly to the user before the LLM sees it. */
    default boolean streamsResultToUser() { return false; }
}
