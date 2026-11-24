package ai.mindconnect.agent.runtime.engine.service;

import java.util.Set;

import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.BackgroundWork;
import ai.mindconnect.agent.runtime.api.port.out.BackgroundWorker;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.ModelSettingsResolver;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionEventPublisher;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.TaskQueue;

/**
 * The engine as a plugin: provides the session and turn services and
 * background work, on a task queue it is handed. Everything it needs beyond
 * the queue it takes from the registry — the session store and the model as
 * services, agents, tools and model settings as contributions — and this is
 * the one place of the engine that looks anything up there.
 */
public final class EnginePlugin implements Plugin {

    private final TaskQueue queue;
    private final SharedStateStore sharedState;

    /**
     * @param queue       where turns and tool calls run; the plugin
     *                    registers its workers on it and does not close it
     * @param sharedState the queue's shared state: the order of a session's
     *                    turns has to hold for every process on the queue
     */
    public EnginePlugin(TaskQueue queue, SharedStateStore sharedState) {
        this.queue = queue;
        this.sharedState = sharedState;
    }

    @Override
    public String name() {
        return "runtime";
    }

    @Override
    public Set<Class<?>> requires() {
        return Set.of(SessionStore.class, ModelGateway.class, ScopeSupplier.class);
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(SessionEventHub.class, () -> new SessionEventHub(
                registry.get(SessionStore.class), registry.get(ScopeSupplier.class),
                () -> registry.all(SessionEventListener.class)));
        registry.provide(SessionEventPublisher.class, () -> registry.get(SessionEventHub.class));
        registry.provide(SessionService.class, () -> new DefaultSessionService(
                registry.get(SessionStore.class), registry.get(SessionEventHub.class)));
        registry.provide(QueuedBackgroundWork.class,
                () -> new QueuedBackgroundWork(queue,
                        () -> registry.all(BackgroundWorker.class), registry.get(ScopeSupplier.class)));
        registry.provide(BackgroundWork.class, () -> registry.get(QueuedBackgroundWork.class));
        // Built once and on first use: by then every plugin is installed, and
        // building it is what puts the workers on the queue.
        registry.provide(TurnService.class, () -> {
            SessionStore sessions = registry.get(SessionStore.class);
            HookRunner hooks = registry::run;
            ToolResolver tools = ToolResolver.firstOf(() -> registry.all(ToolResolver.class));
            EventDelivery events = new EventDelivery(new TurnEventHub(), registry.get(SessionEventHub.class));
            TurnAcceptor acceptor = new TurnAcceptor(sessions,
                    () -> registry.all(ModelSettingsResolver.class), hooks);
            ScopeSupplier scope = registry.get(ScopeSupplier.class);
            TurnTaskMapper turns = new TurnTaskMapper(queue, scope);
            queue.register(TurnTaskMapper.TYPE, new CallerBoundWorker(
                    new TurnWorker(sessions, tools, registry.get(ModelGateway.class), hooks, events, turns,
                            new InstructionRenderer(registry.get(PromptRenderer.class),
                                    () -> registry.all(PromptVariableProvider.class))),
                    scope));
            queue.register(ToolCallWorker.TYPE, new CallerBoundWorker(
                    new ToolCallWorker(sessions, tools, hooks, turns, acceptor), scope));
            queue.register(QueuedBackgroundWork.TYPE, new CallerBoundWorker(
                    registry.get(QueuedBackgroundWork.class), scope));
            return new DefaultTurnService(acceptor, sessions, queue, sharedState, events, scope);
        });
    }
}
