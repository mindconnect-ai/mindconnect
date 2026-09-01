package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;

public final class ListAgentsToolFactory implements ToolFactory {
    private AgentDefinitionRepository definitionRepository;

    @Override public String name() { return "list_agents"; }

    @Override public String group() { return "agents"; }

    @Override public void bind(ToolEnvironment env) {
        this.definitionRepository = env.require(AgentDefinitionRepository.class);
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ListAgentsTool(definitionRepository, scope.namespace(), scope.agentDefinitionId());
    }
}
