package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;


public final class DocumentOutlineToolFactory extends DocBaseDirs.FileRooted {
    @Override public String name() { return "document_outline"; }

    @Override public String group() { return "documents"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new DocumentOutlineTool(files(agentTool, scope),
                SharedDocumentReader.INSTANCE);
    }
}
