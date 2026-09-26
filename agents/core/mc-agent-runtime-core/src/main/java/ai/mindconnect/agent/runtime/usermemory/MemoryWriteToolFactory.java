package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

public final class MemoryWriteToolFactory implements ToolFactory {

    private UserMemoryService service;

    @Override public String name() { return MemoryWriteTool.NAME; }

    @Override public String group() { return MemoryIndex.TOOL_GROUP; }

    @Override
    public void bind(ToolEnvironment env) {
        this.service = env.require(UserMemoryService.class);
    }

    @Override
    public boolean isAvailable() { return service != null; }

    /** Which memory the tool works on — the user's own, this agent's, or both; see {@link MemoryReach}. */
    @Override
    public java.util.Map<String, Object> overridesSchema() { return MemoryReach.overridesSchema(); }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new MemoryWriteTool(service, scope.userId(), scope.agentId(), scope.sessionId(), MemoryReach.of(agentTool));
    }
}
