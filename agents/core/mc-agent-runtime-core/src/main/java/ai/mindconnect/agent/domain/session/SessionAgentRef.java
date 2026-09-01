package ai.mindconnect.agent.domain.session;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;

import java.util.List;
import java.util.UUID;

/**
 * A session bound to an agent from the registry, optionally with a different
 * model or tool selection for this chat alone.
 *
 * <p>{@link #id()} is the agent's own id, so the workspace and the messages
 * stay with the agent and {@code ?agentId=} keeps finding these sessions.
 *
 * <p>The system prompt used to be excluded here, on the grounds that a ref
 * whose prompt was replaced is an inline agent wearing someone else's name,
 * and that detaching into an {@link InlineSessionAgent} was the honest way to
 * change it. That reasoning predates {@code callableAgents}: detaching now
 * silently drops the roster the agent was given, so a chat that edits its
 * prompt would quietly regain the run of every agent in the namespace. Keeping
 * the binding and overriding the prompt is the lesser evil — and the name is
 * kept honest by showing the override in the chat header rather than by
 * forbidding it.
 */
public record SessionAgentRef(
        UUID id,
        boolean main,
        String label,
        /** {@code null} = the agent's own. */
        String llmConfigName,
        /** {@code null} = the agent's own. */
        List<AgentTool> tools,
        /** {@code null} = the agent's own. */
        AgentDefinition.ToolSearchConfig toolSearch,
        /** {@code null} = the agent's own. Anything else is shown as an override. */
        String systemPrompt
) implements SessionAgent {

    /** Pre-override constructor: the agent's own prompt. */
    public SessionAgentRef(UUID id, boolean main, String label, String llmConfigName,
                           List<AgentTool> tools, AgentDefinition.ToolSearchConfig toolSearch) {
        this(id, main, label, llmConfigName, tools, toolSearch, null);
    }

    /** Whether this chat runs on something other than the agent's own prompt. */
    public boolean hasPromptOverride() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    /** The agent this session runs — the id is the reference. */
    public UUID agentId() {
        return id;
    }
}
