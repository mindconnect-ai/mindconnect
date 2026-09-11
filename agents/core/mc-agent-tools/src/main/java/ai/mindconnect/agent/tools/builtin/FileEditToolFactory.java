package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

public final class FileEditToolFactory extends FileRootedToolFactory {
    @Override public String name() { return "file_edit"; }
    @Override public String group() { return "files"; }
    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new FileEditTool(BaseDirs.roots(scope, agentTool, defaultBaseDir));
    }
}
