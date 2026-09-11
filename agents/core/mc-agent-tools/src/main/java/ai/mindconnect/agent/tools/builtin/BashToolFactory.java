package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;


public final class BashToolFactory extends FileRootedToolFactory {
    @Override public String name() { return "bash"; }

    @Override public String group() { return "files"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new BashTool(BaseDirs.roots(scope, agentTool, defaultBaseDir), scope == null ? null : scope.sessionId());
    }
}
