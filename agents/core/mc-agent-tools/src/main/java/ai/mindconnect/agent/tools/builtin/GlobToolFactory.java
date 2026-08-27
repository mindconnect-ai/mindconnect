package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.nio.file.Path;

public final class GlobToolFactory extends FileRootedToolFactory {
    @Override public String name() { return "glob"; }

    @Override public String group() { return "files"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new GlobTool(Path.of(BaseDirs.resolve(agentTool, defaultBaseDir)));
    }
}
