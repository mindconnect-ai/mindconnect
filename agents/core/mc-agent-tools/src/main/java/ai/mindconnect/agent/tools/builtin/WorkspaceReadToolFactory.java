package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

public final class WorkspaceReadToolFactory extends WorkspaceToolFactory {
    @Override public String name() { return "workspace_read"; }

    @Override public String group() { return "workspace"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new WorkspaceReadTool(workspaceStore, scope.agentDefinitionId(), scope.userId(), scope.sessionId());
    }
}
