package ai.mindconnect.agent.runtime.domain.session;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.tool.AgentTool;

import java.util.List;

/**
 * An agent that exists only inside its session — the shape a chat takes when
 * the user chose a model and tools rather than an agent from the registry.
 *
 * <p>No memory config: it is the one setting that quietly breaks long chats
 * when it is wrong, and it is not a knob an end user turns. The system default
 * applies.
 */
public record InlineSessionAgent(
        /** An inline agent is an agent without a stored definition; its id is minted with the session. */
        AgentId id,
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

    /**
     * Builds one from tool <em>names</em> — the shape every caller actually
     * has.
     *
     * <p>The id is minted here. A tool call learns the agent from its
     * {@code ToolCallScope}, which is also what the {@code AGENT_USER}
     * workspace is keyed by — so this id is the one the chat's persistent
     * memory lives under.
     *
     * @param toolSearch whether the chat may find the remaining tools itself
     */
    public static InlineSessionAgent of(String label, String systemPrompt,
                                        String llmConfigName, List<String> toolNames, boolean toolSearch) {
        AgentId id = AgentId.random();
        List<AgentTool> tools = (toolNames == null ? List.<String>of() : toolNames).stream()
                .map(name -> AgentTool.of(name))
                .toList();
        var search = toolSearch
                ? new AgentDefinition.ToolSearchConfig(true, List.of("*"))
                : AgentDefinition.ToolSearchConfig.OFF;
        return new InlineSessionAgent(id, true, label, systemPrompt, llmConfigName, tools, search);
    }

    /** The same agent under a new id — used when a chat detaches from one. */
    public InlineSessionAgent withTools(List<String> toolNames, boolean toolSearch) {
        List<AgentTool> rebuilt = (toolNames == null ? List.<String>of() : toolNames).stream()
                .map(name -> AgentTool.of(name))
                .toList();
        var search = toolSearch
                ? new AgentDefinition.ToolSearchConfig(true, List.of("*"))
                : AgentDefinition.ToolSearchConfig.OFF;
        return new InlineSessionAgent(id, main, label, systemPrompt, llmConfigName, rebuilt, search);
    }

    public InlineSessionAgent withLlmConfigName(String llmConfigName) {
        return new InlineSessionAgent(id, main, label, systemPrompt, llmConfigName, tools, toolSearch);
    }
}
