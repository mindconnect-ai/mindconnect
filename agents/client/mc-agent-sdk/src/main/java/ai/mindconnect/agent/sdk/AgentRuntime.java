package ai.mindconnect.agent.sdk;

import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;

/**
 * An agent runtime running in this process. What a program does with it is
 * the runtime API: open a session, submit a turn, follow it. This class adds
 * nothing to that — it holds what was put together and closes it.
 */
public final class AgentRuntime implements AutoCloseable {

    private final PluginRegistry registry;
    private final LocalTaskQueue queue;

    AgentRuntime(PluginRegistry registry, LocalTaskQueue queue) {
        this.registry = registry;
        this.queue = queue;
    }

    public static AgentRuntimeBuilder builder() {
        return new AgentRuntimeBuilder();
    }

    public SessionService sessions() {
        return registry.get(SessionService.class);
    }

    public TurnService turns() {
        return registry.get(TurnService.class);
    }

    /** Stops the workers; turns that are running are not waited for. */
    @Override
    public void close() {
        queue.close();
        registry.close();
    }
}
