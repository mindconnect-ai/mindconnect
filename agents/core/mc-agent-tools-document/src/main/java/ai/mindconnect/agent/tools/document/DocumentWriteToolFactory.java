package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.nio.file.Path;

public final class DocumentWriteToolFactory extends DocBaseDirs.FileRooted {
    @Override public String name() { return "document_write"; }

    @Override public String group() { return "documents"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new DocumentWriteTool(Path.of(DocBaseDirs.resolve(agentTool, defaultBaseDir)));
    }
}
