package ai.mindconnect.agent.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.permission.InMemoryApprovalMemory;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionEffect;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.store.InMemorySessionStorePlugin;
import ai.mindconnect.agent.runtime.prompt.PebblePromptPlugin;
import ai.mindconnect.agent.runtime.engine.service.EnginePlugin;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemorySharedStateStore;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two namespaces that hold a session of the same id. The namespace is not
 * on the session; it is whatever is current where the caller stands, and it
 * has to arrive on the workers that run the turn — through tool calls and
 * sub-agents — or one tenant reads the other's conversation.
 */
class NamespaceIsolationTest {

    private static final SessionId SAME_ID = SessionId.of("s1");
    private static final AgentDefinition RESEARCHER = new AgentDefinition("researcher", "Looks things up.",
            "You research.", ModelSettings.NONE, List.of(), 5, Map.of());
    private static final AgentDefinition LEAD = new AgentDefinition("lead", "Leads.", "You lead.",
            ModelSettings.NONE, List.of(), 5, Map.of(SubAgentPlugin.SUB_AGENTS, List.of("researcher")));

    private final Deque<TurnMessage> script = new ArrayDeque<>();
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    /** The organisation in the scope of each model call, in order. */
    private final List<String> organisations = new CopyOnWriteArrayList<>();
    private final ScopeSupplier namespaces = new ThreadBoundScope();
    private final LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore());
    private final PluginRegistry registry = new DefaultPluginRegistry();
    private TurnService turns;

    @BeforeEach
    void assemble_a_runtime_for_several_namespaces() {
        registry.install(provides(into -> {
            into.provide(ScopeSupplier.class, () -> namespaces);
            into.provide(ModelGateway.class, () -> new ModelGateway() {
                @Override
                public synchronized LlmAnswer ask(ModelRequest request, TurnEventListener events) {
                    requests.add(request);
                    organisations.add(namespaces.get().attribute("organisation").orElse("none"));
                    return LlmAnswer.of(List.of(script.pop()), new Usage(10, 1));
                }

                @Override
                public void cancel(TurnId turnId) {
                }
            });
            into.contribute(AgentResolver.class, AgentResolver.of(List.of(LEAD, RESEARCHER)));
        }));
        registry.install(new InMemorySessionStorePlugin());
        registry.install(new PebblePromptPlugin());
        registry.install(new EnginePlugin(queue, new InMemorySharedStateStore()));
        registry.install(new SubAgentPlugin(key -> true, SubAgentPlugin.DEFAULT_MAX_DEPTH));
        registry.install(new PermissionPlugin(PermissionEffect.ALLOW, new InMemoryApprovalMemory(namespaces)));
        registry.start();
        turns = registry.get(TurnService.class);
        for (String namespace : List.of("a", "b")) {
            in(namespace, () -> {
                registry.get(SessionStore.class).create(new Session(SAME_ID, UserId.of("ada"), null, LEAD,
                        Map.of(), Instant.EPOCH));
                return null;
            });
        }
    }

    @AfterEach
    void stop() {
        queue.close();
        registry.close();
    }

    @Test
    void a_turn_with_a_sub_agent_stays_in_the_namespace_it_was_submitted_in() {
        List<SessionEvent> heardInA = new CopyOnWriteArrayList<>();
        in("a", () -> {
            registry.get(SessionService.class).lifecycle(SAME_ID, true, heardInA::add);
            return registry.get(SessionService.class).stream(SAME_ID, true, heardInA::add);
        });
        script.add(TurnMessage.toolCalls(List.of(
                new TurnMessage.ToolCall("c1", "run_agent", Map.of("name", "researcher", "message", "Find it.")))));
        script.add(TurnMessage.assistant("Found."));
        script.add(TurnMessage.assistant("Answer for A."));
        script.add(TurnMessage.assistant("Answer for B."));

        Turn inA = in("a", () -> awaitEnd(turns.submit(TurnRequest.text(SAME_ID, "Question of A"))));
        int eventsOfA = heardInA.size();
        Turn inB = in("b", () -> awaitEnd(turns.submit(TurnRequest.text(SAME_ID, "Question of B"))));

        assertThat(inA.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(inB.status()).isEqualTo(TurnStatus.COMPLETED);
        // What the model was sent for B is B's conversation alone, though A's session has the same id.
        assertThat(requests.getLast().messages()).extracting(TurnMessage::text).containsExactly("Question of B");
        assertThat(in("a", () -> conversation())).contains("Question of A", "Found.", "Answer for A.")
                .doesNotContain("Question of B", "Answer for B.");
        assertThat(in("b", () -> conversation())).containsExactly("Question of B", "Answer for B.");
        assertThat(eventsOfA).isPositive();
        assertThat(heardInA).as("a listener in A hears nothing of B's session of the same id").hasSize(eventsOfA);
    }

    @Test
    void a_turn_its_tool_call_and_its_sub_agent_act_in_the_whole_scope_it_was_submitted_in() throws Exception {
        script.add(TurnMessage.toolCalls(List.of(
                new TurnMessage.ToolCall("c1", "run_agent", Map.of("name", "researcher", "message", "Find it.")))));
        script.add(TurnMessage.assistant("Found."));
        script.add(TurnMessage.assistant("Answer."));
        Scope submitted = Scope.of(new Namespace("a"), UserId.of("ada")).withAttribute("organisation", "acme");

        Turn turn = namespaces.callIn(submitted, () -> awaitEnd(turns.submit(TurnRequest.text(SAME_ID, "Question"))));

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        // The lead's call, the sub-agent's — submitted by the tool call — and the lead's answer.
        assertThat(organisations).containsExactly("acme", "acme", "acme");
    }

    @Test
    void a_turn_does_not_exist_for_another_namespace() {
        script.add(TurnMessage.assistant("Answer for A."));
        Turn inA = in("a", () -> awaitEnd(turns.submit(TurnRequest.text(SAME_ID, "Question of A"))));

        assertThat(in("b", () -> turns.find(inA.id()))).isEmpty();
        assertThat(in("b", () -> turns.messages(inA.id()))).isEmpty();
        assertThat(in("a", () -> turns.find(inA.id()))).isPresent();
    }

    @Test
    void without_a_namespace_nothing_is_submitted() {
        assertThatThrownBy(() -> turns.submit(TurnRequest.text(SAME_ID, "Question of nobody")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("namespace");
        assertThat(requests).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private <T> T in(String namespace, Supplier<T> body) {
        try {
            return namespaces.callIn(Scope.of(new Namespace(namespace)), body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The scope of the thread, as a server binds it: for the length of a
     * request by whoever receives it, and by the engine's workers for the
     * length of a task. A thread nobody bound has none and is told so.
     */
    private static final class ThreadBoundScope implements ScopeSupplier {

        private final ThreadLocal<Scope> bound = new ThreadLocal<>();

        @Override
        public Scope get() {
            Scope scope = bound.get();
            if (scope == null) {
                throw new IllegalStateException("No namespace is bound to this thread");
            }
            return scope;
        }

        @Override
        public <T> T callIn(Scope scope, java.util.concurrent.Callable<T> body) throws Exception {
            Scope before = bound.get();
            bound.set(scope);
            try {
                return body.call();
            } finally {
                if (before == null) {
                    bound.remove();
                } else {
                    bound.set(before);
                }
            }
        }
    }

    private List<String> conversation() {
        return registry.get(SessionStore.class).messages(SAME_ID).stream()
                .filter(message -> message.type() == TurnMessage.Type.USER
                        || message.type() == TurnMessage.Type.ASSISTANT
                        || message.type() == TurnMessage.Type.TOOL_RESULT)
                .map(TurnMessage::text)
                .toList();
    }

    private Turn awaitEnd(TurnId turnId) {
        long deadline = System.currentTimeMillis() + 5_000;
        Turn turn = turns.find(turnId).orElseThrow();
        while (!turn.status().isFinal() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            turn = turns.find(turnId).orElseThrow();
        }
        return turn;
    }

    private static Plugin provides(java.util.function.Consumer<PluginRegistry> install) {
        return new Plugin() {
            @Override
            public String name() {
                return "test-parts";
            }

            @Override
            public void install(PluginRegistry registry) {
                install.accept(registry);
            }
        };
    }
}
