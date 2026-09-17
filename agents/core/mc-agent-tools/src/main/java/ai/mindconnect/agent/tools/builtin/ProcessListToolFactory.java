package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;

public class ProcessListToolFactory implements ToolFactory {
    @Override public String name() { return "process_list"; }
    @Override public String group() { return "files"; }
    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ProcessListTool(scope == null ? null : scope.sessionId());
    }
}
