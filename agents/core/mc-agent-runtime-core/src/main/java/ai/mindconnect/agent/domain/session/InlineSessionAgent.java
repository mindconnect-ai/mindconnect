package ai.mindconnect.agent.domain.session;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;

import java.util.List;
import java.util.UUID;

/**
 * An agent that exists only inside its session — the shape a chat takes when
 * the user chose a model and tools rather than an agent from the registry.
 *
 * <p>No memory config: it is the one setting that quietly breaks long chats
 * when it is wrong, and it is not a knob an end user turns. The system default
 * applies.
 */
public record InlineSessionAgent(
        UUID id,
        boolean main,
        String label,
        String systemPrompt,
        String llmConfigName,
        List<AgentTool> tools,
        AgentDefinition.ToolSearchConfig toolSearch
) implements SessionAgent {

    public InlineSessionAgent {
        if (tools == null) tools = List.of();
        if (label == null || label.isBlank()) label = "Chat";
    }
}
