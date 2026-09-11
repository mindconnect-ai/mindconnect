package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;


public final class DocumentSectionsToolFactory extends DocBaseDirs.FileRooted {
    @Override public String name() { return "document_sections"; }

    @Override public String group() { return "documents"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new DocumentSectionsTool(DocBaseDirs.roots(scope, agentTool, defaultBaseDir),
                SharedDocumentReader.INSTANCE);
    }
}
