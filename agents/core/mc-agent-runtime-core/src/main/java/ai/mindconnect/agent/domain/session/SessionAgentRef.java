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
 * <p>The system prompt is deliberately not overridable: a ref whose prompt was
 * replaced would be an inline agent wearing someone else's name, and "sessions
 * of agent X" would stop meaning anything. Changing the prompt detaches the
 * chat into an {@link InlineSessionAgent} instead.
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
        AgentDefinition.ToolSearchConfig toolSearch
) implements SessionAgent {

    /** The agent this session runs — the id is the reference. */
    public UUID agentId() {
        return id;
    }
}
