package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.nio.file.Path;

public final class DocumentFileReadToolFactory extends DocBaseDirs.FileRooted {
    @Override public String name() { return "document_file_read"; }

    @Override public String group() { return "documents"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new DocumentFileReadTool(Path.of(DocBaseDirs.resolve(agentTool, defaultBaseDir)));
    }
}
