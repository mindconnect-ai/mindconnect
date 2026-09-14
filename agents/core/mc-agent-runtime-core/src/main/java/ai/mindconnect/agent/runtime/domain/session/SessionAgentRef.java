package ai.mindconnect.agent.runtime.domain.session;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.tool.AgentTool;

import java.util.List;

/**
 * A session bound to an agent from the registry, optionally with a different
 * model, tool selection or roster for this chat alone — the agent is the
 * template the chat starts from.
 *
 * <p>{@link #id()} is the agent's own id, so the messages
 * stay with the agent and {@code ?agentId=} keeps finding these sessions.
 *
 * <p>The system prompt used to be excluded here, on the grounds that a ref
 * whose prompt was replaced is an inline agent wearing someone else's name,
 * and that detaching into an {@link InlineSessionAgent} was the honest way to
 * change it. That reasoning predates {@code callableAgents}: detaching now
 * silently drops the roster the agent was given, so a chat that edits its
 * prompt would quietly regain the run of every agent. Keeping
 * the binding and overriding the prompt is the lesser evil — and the name is
 * kept honest by showing the override in the chat header rather than by
 * forbidding it.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record SessionAgentRef(
        AgentId id,
        boolean main,
        String label,
        /** {@code null} = the agent's own. */
        String llmConfigName,
        /** {@code null} = the agent's own. */
        List<AgentTool> tools,
        /** {@code null} = the agent's own. Anything else is shown as an override. */
        String systemPrompt,
        /**
         * The agents this chat may call, by name — {@code null} = the agent's
         * own roster, an empty list = nobody. The chat's agent is a template:
         * once the chat picks its own, they replace the agent's, and may name
         * agents the agent itself never had.
         */
        List<String> callableAgents
) implements SessionAgent {

    public SessionAgentRef {
        callableAgents = callableAgents == null ? null : List.copyOf(callableAgents);
    }

    /** The agent's own prompt and roster. */
    public SessionAgentRef(AgentId id, boolean main, String label, String llmConfigName,
                           List<AgentTool> tools) {
        this(id, main, label, llmConfigName, tools, null, null);
    }

    /** The agent's own roster. */
    public SessionAgentRef(AgentId id, boolean main, String label, String llmConfigName,
                           List<AgentTool> tools, String systemPrompt) {
        this(id, main, label, llmConfigName, tools, systemPrompt, null);
    }

    public boolean hasPromptOverride() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    /** The agent this session runs — the id is the reference. */
    public AgentId agentId() {
        return id;
    }
}
