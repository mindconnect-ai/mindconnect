package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.jackson.JsonMapperFactory;


public final class DocumentSectionsToolFactory extends DocBaseDirs.FileRooted {
    @Override public String name() { return "document_sections"; }

    @Override public String group() { return "documents"; }

    private JsonMapperFactory json;

    @Override public void bind(ToolEnvironment env) {
        super.bind(env);
        this.json = env.require(JsonMapperFactory.class);
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new DocumentSectionsTool(files(agentTool, scope),
                SharedDocumentReader.INSTANCE, json);
    }
}
