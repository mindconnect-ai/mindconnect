package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.ToolRegistry;

/**
 * Late-bound handle to the {@link ToolRegistry}, breaking the construction
 * cycle for tools that need the registry itself: {@link SpiToolRegistry}
 * builds its factories from the {@code ToolEnvironment}, so the registry
 * cannot be a direct environment service. The host wiring registers this ref
 * in the environment first, constructs the registry, then {@link #set}s it —
 * by the time any tool executes, the ref resolves.
 */
public final class ToolRegistryRef {

    private volatile ToolRegistry registry;

    public void set(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolRegistry get() {
        ToolRegistry current = registry;
        if (current == null) {
            throw new IllegalStateException("ToolRegistry not yet initialised");
        }
        return current;
    }
}
