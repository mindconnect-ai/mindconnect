package ai.mindconnect.agent.protocol.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.management.ManagementPlugin;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * A runtime in memory with agents kept in the management, a model that says
 * what the test scripted, and the backend on it — what a test of the
 * protocol on the runtime needs and nothing else.
 */
final class BackendFixture implements AutoCloseable {

    private final Deque<TurnMessage> script = new ArrayDeque<>();
    private final AgentRuntime runtime;

    BackendFixture(List<Tool> tools) {
        ScopeSupplier namespaces = ScopeSupplier.fixed(new Namespace("test"));
        SessionRepository sessions = new InMemorySessionRepository(namespaces);
        AgentRepository agents = new InMemoryAgentRepository(namespaces);
        MessageRepository messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
        var builder = AgentRuntime.builder()
                .scope(namespaces)
                .model(new ModelGateway() {
                    @Override
                    public LlmAnswer ask(ModelRequest request, TurnEventListener events) {
                        synchronized (script) {
                            return LlmAnswer.of(List.of(script.pop()), new Usage(10, 1));
                        }
                    }

                    @Override
                    public void cancel(TurnId turnId) {
                    }
                })
                .plugin(new Plugin() {
                    @Override
                    public String name() {
                        return "test-stores";
                    }

                    @Override
                    public void install(PluginRegistry registry) {
                        registry.provide(SessionRepository.class, () -> sessions);
                        registry.provide(AgentRepository.class, () -> agents);
                        registry.provide(MessageRepository.class, () -> messages);
                    }
                })
                .plugin(new ManagementPlugin())
                .without(SessionTitlePlugin.class);
        tools.forEach(builder::tool);
        runtime = builder.build();
    }

    AgentRuntime runtime() {
        return runtime;
    }

    /** What the model says next, in order. */
    void says(TurnMessage... answers) {
        synchronized (script) {
            script.addAll(List.of(answers));
        }
    }

    ManagedAgent agent(AgentDefinition definition) {
        return runtime.service(AgentManagement.class).create(ManagedAgent.of(definition));
    }

    AgentRuntimeBackend backend(Supplier<UserId> caller) {
        return AgentRuntimeBackend.on(runtime, caller);
    }

    @Override
    public void close() {
        runtime.close();
    }
}
