package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

public final class GrepToolFactory extends FileRootedToolFactory {
    @Override public String name() { return "grep"; }
    @Override public String group() { return "files"; }
    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new GrepTool(BaseDirs.roots(scope, agentTool, defaultBaseDir));
    }
}
