package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;


public final class FileWriteToolFactory extends FileRootedToolFactory {
    @Override public String name() { return "file_write"; }

    @Override public String group() { return "files"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new FileWriteTool(BaseDirs.roots(scope, agentTool, defaultBaseDir));
    }
}
