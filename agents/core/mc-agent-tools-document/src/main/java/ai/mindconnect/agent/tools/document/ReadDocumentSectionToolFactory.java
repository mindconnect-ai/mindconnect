package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;


public final class ReadDocumentSectionToolFactory extends DocBaseDirs.FileRooted {
    @Override public String name() { return "read_document_section"; }

    @Override public String group() { return "documents"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ReadDocumentSectionTool(DocBaseDirs.roots(scope, agentTool, defaultBaseDir),
                SharedDocumentReader.INSTANCE);
    }
}
