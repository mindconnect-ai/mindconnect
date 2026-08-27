package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;

public final class CurrentDateTimeToolFactory implements ToolFactory {
    @Override public String name() { return "get_current_datetime"; }

    @Override public String group() { return "utilities"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) { return new CurrentDateTimeTool(); }
}
