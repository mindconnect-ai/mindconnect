package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

/**
 * Registered in {@code META-INF/services} and named in the manifest like the
 * dice. Unavailable on a host without the demo feature — there is no store
 * to save into.
 */
public final class StateToolFactory implements ToolFactory {

    private DemoStore<Adventure> adventures;

    @Override
    @SuppressWarnings("unchecked")
    public void bind(ToolEnvironment env) {
        adventures = env.get(AdventureStore.class).map(AdventureStore::store).orElse(null);
    }

    @Override
    public boolean isAvailable() {
        return adventures != null;
    }

    @Override
    public String name() {
        return "demo_state";
    }

    @Override
    public String group() {
        return "demo";
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        String sessionId = scope == null || scope.sessionId() == null ? null : scope.sessionId().value();
        return new StateTool(adventures, sessionId);
    }
}
