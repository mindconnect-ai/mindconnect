package ai.mindconnect.agent.sdk;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * An agent runtime running in this process. The runtime is its own
 * container: what its plugins provide is asked for here, by type — the
 * runtime API to open a session, submit a turn and follow it, and whatever
 * else was put together with it. This class adds nothing to that — it holds
 * what was put together and closes it.
 */
public final class AgentRuntime implements AutoCloseable {

    private final PluginRegistry registry;
    private final Runnable stopWorkers;

    /**
     * @param registry    the started registry the runtime's plugins were installed in
     * @param stopWorkers stops the workers that run the turns, where they are this runtime's own
     */
    public AgentRuntime(PluginRegistry registry, Runnable stopWorkers) {
        this.registry = registry;
        this.stopWorkers = stopWorkers;
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

    /** What a plugin provides under this type; a runtime that was put together without it fails here. */
    public <T> T service(Class<T> type) {
        return registry.get(type);
    }

    /** Like {@link #service}, for a part a runtime may have been put together without. */
    public <T> Optional<T> find(Class<T> type) {
        return registry.find(type);
    }

    /** Everything the plugins contributed under this type, in the order contributed. */
    public <T> List<T> all(Class<T> type) {
        return registry.all(type);
    }

    /** Stops the workers; turns that are running are not waited for. */
    @Override
    public void close() {
        stopWorkers.run();
        registry.close();
    }
}
