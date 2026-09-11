package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;

public final class ProcessKillToolFactory implements ToolFactory {
    @Override public String name() { return "process_kill"; }
    @Override public String group() { return "files"; }
    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ProcessKillTool(scope == null ? null : scope.sessionId());
    }
}
