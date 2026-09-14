package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;

public final class ListAgentsToolFactory implements ToolFactory {
    private AgentDefinitionRepository definitionRepository;
    /** Optional: without it the caller is read from the registry alone. */
    private AgentSessionRepository sessions;

    @Override public String name() { return "list_agents"; }

    @Override public String group() { return "agents"; }

    @Override public void bind(ToolEnvironment env) {
        this.definitionRepository = env.require(AgentDefinitionRepository.class);
        this.sessions = env.get(AgentSessionRepository.class).orElse(null);
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return ListAgentsTool.forCaller(definitionRepository, () -> caller(scope), scope.workingDir());
    }

    /**
     * The caller as its session runs it. The registry's definition alone
     * missed two rosters {@code run_agent} does honour: the one a chat
     * narrowed its agent to, and the one an inline agent carries — whose id
     * the registry has never heard of, which listed every agent.
     */
    private AgentDefinition caller(ToolCallScope scope) {
        if (sessions != null && scope.sessionId() != null) {
            var session = sessions.findById(scope.sessionId()).orElse(null);
            if (session != null) {
                try {
                    return new SessionAgentResolver(definitionRepository).resolve(session);
                } catch (RuntimeException gone) {
                    // The agent behind the session was deleted: fall through.
                }
            }
        }
        return scope.agentId() == null ? null : definitionRepository.findById(scope.agentId()).orElse(null);
    }
}
