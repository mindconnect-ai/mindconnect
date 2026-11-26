package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

public final class TodoReadToolFactory implements ToolFactory {

    private TodoListService service;

    @Override public String name() { return TodoReadTool.NAME; }

    @Override public String group() { return "todo"; }

    @Override
    public void bind(ToolEnvironment env) {
        this.service = env.require(TodoListService.class);
    }

    @Override
    public boolean isAvailable() { return service != null; }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new TodoReadTool(service, scope.sessionId());
    }
}
