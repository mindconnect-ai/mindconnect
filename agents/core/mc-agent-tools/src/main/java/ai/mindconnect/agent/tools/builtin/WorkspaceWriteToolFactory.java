package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

public final class WorkspaceWriteToolFactory extends WorkspaceToolFactory {
    @Override public String name() { return "workspace_write"; }

    @Override public String group() { return "workspace"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new WorkspaceWriteTool(workspaceStore, scope.agentDefinitionId(), scope.userId(), scope.sessionId());
    }
}
