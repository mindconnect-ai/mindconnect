package ai.mindconnect.demo.agent.simple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.management.ManagementPlugin;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.infrastructure.InMemoryStorePlugin;
import ai.mindconnect.agent.runtime.infrastructure.LlmPlugin;
import ai.mindconnect.agent.runtime.infrastructure.ToolRegistryPlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tools.todo.TodoPlugin;
import ai.mindconnect.agent.tools.usermemory.UserMemoryPlugin;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.jackson.JacksonPlugin;
import ai.mindconnect.jackson.JsonMapperFactory;
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
                    // The runtime's mapper as the demos always had it.
                    .plugin(new JacksonPlugin(List.of(mapper -> mapper.addModule(new JavaTimeModule())
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))))
                    .plugin(new InMemoryStorePlugin(Files.createTempDirectory("mc-agent-demo"), 50))
                    // The model of the runtime: the stored LLM configuration an agent names.
                    .plugin(new LlmPlugin(null, EnvVarResolver.system()))
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
            runtime.service(LlmConfigRepository.class).save(json(runtime).readValue(read(resource), LlmConfig.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An agent file in the stored form or the one written before 1.0. */
    static void agentFromClasspath(AgentRuntime runtime, String resource) {
        runtime.service(AgentManagement.class).create(new AgentJson(json(runtime)).read(read(resource)));
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
        CompletableFuture<String> answer = new CompletableFuture<>();
        // The session's one stream tells all of it: what the turn and its sub-agents do, and how the turn ends.
        Subscription following = runtime.sessions().stream(session, true, event -> {
            events.accept(event);
            if (!event.sessionId().equals(session) || event.ofSubAgent()) {
                return;
            }
            switch (event.known()) {
                case SessionStreamEvent.Done done when done.status() == TurnStatus.COMPLETED -> answer.complete(done.text());
                case SessionStreamEvent.Done done ->
                        answer.completeExceptionally(new IllegalStateException("The turn ended " + done.status()));
                case SessionStreamEvent.Error error -> answer.completeExceptionally(new IllegalStateException(error.message()));
                default -> { }
            }
        });
        runtime.turns().submit(TurnRequest.text(session, question));
        try {
            return answer.join();
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause() instanceof RuntimeException failure ? failure : e;
        } finally {
            following.close();
        }
    }

    private static ObjectMapper json(AgentRuntime runtime) {
        return runtime.service(JsonMapperFactory.class).create().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
