package ai.mindconnect.demo.agent.simple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.management.ManagementPlugin;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.runtime.infrastructure.InMemoryStorePlugin;
import ai.mindconnect.agent.runtime.infrastructure.LlmPlugin;
import ai.mindconnect.agent.runtime.infrastructure.ToolRegistryPlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tools.todo.TodoPlugin;
import ai.mindconnect.agent.tools.usermemory.UserMemoryPlugin;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * What the demos share: a runtime in the process, made of the same plugins a
 * server installs — stores in memory, the LLM gateway on stored
 * configurations, the tools on the classpath, agents kept in the management
 * — and the few lines that ask an agent a question and wait for its answer.
 */
final class DemoRuntime {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private DemoRuntime() {
    }

    /** @param toolSettings what a tool reads by key, e.g. {@code tavilyApiKey} */
    static AgentRuntime build(Map<String, String> toolSettings) {
        ScopeSupplier scope = ScopeSupplier.fixed(Scope.of(new Namespace("demo")));
        try {
            AgentRuntime runtime = AgentRuntime.builder()
                    .plugin(new Plugin() {
                        @Override
                        public String name() {
                            return "scope";
                        }

                        @Override
                        public void install(PluginRegistry registry) {
                            registry.provide(ScopeSupplier.class, () -> scope);
                        }
                    })
                    .plugin(new InMemoryStorePlugin(Files.createTempDirectory("mc-agent-demo"), 50))
                    // The model of the runtime: the stored LLM configuration an agent names.
                    .plugin(new LlmPlugin(null, EnvVarResolver.system(), JSON))
                    .plugin(new ToolRegistryPlugin(toolSettings, null, type -> Optional.empty()))
                    .plugin(new ManagementPlugin())
                    // The todo and memory tools on the classpath work on these two.
                    .plugin(new TodoPlugin())
                    .plugin(new UserMemoryPlugin())
                    .build();
            // The tools bind their providers once the runtime's services are there.
            runtime.service(ToolRegistryPlugin.class).warmUp();
            return runtime;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void llmConfigFromClasspath(AgentRuntime runtime, String resource) {
        try {
            runtime.service(LlmConfigRepository.class).save(JSON.readValue(read(resource), LlmConfig.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An agent file in the stored form or the one written before 1.0. */
    static void agentFromClasspath(AgentRuntime runtime, String resource) {
        runtime.service(AgentManagement.class).create(new AgentJson(JSON).read(read(resource)));
    }

    static void agent(AgentRuntime runtime, AgentDefinition definition) {
        runtime.service(AgentManagement.class).create(ManagedAgent.of(definition));
    }

    /**
     * Asks the stored agent of that name in a session of its own and waits
     * for the answer.
     *
     * @param events what happens in the session meanwhile, its sub-agents' sessions included
     */
    static String ask(AgentRuntime runtime, String agentName, String user, String question,
                      Consumer<SessionEvent> events) {
        ManagedAgent agent = runtime.service(AgentManagement.class).findByName(agentName)
                .orElseThrow(() -> new IllegalArgumentException("No agent named " + agentName));
        SessionId session = runtime.service(SessionManagement.class).open(UserId.of(user), null,
                new SessionAgent.Ref(agent.id()), null, SessionDirectories.NONE).id();
        Subscription heard = runtime.sessions().subscribe(
                ai.mindconnect.agent.runtime.api.port.in.SessionEventFilter.session(session).withChildSessions().withStream(), events::accept);
        TurnId turnId = runtime.turns().submit(TurnRequest.text(session, question));
        CompletableFuture<Turn> ended = new CompletableFuture<>();
        Subscription following = runtime.turns().subscribe(turnId, new TurnListener() {
            @Override
            public void onEvent(TurnEvent event) {
            }

            @Override
            public void onFinished(Turn turn) {
                ended.complete(turn);
            }
        });
        // A turn that ended before anyone listened is asked how it stands.
        runtime.turns().find(turnId).filter(turn -> turn.status().isFinal()).ifPresent(ended::complete);
        try {
            Turn turn = ended.join();
            if (turn.status() != TurnStatus.COMPLETED || turn.outcome() == null) {
                throw new IllegalStateException(turn.error() != null ? turn.error()
                        : "The turn ended " + turn.status());
            }
            return turn.outcome().text();
        } finally {
            following.close();
            heard.close();
        }
    }

    private static String read(String resource) {
        try (InputStream in = DemoRuntime.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
