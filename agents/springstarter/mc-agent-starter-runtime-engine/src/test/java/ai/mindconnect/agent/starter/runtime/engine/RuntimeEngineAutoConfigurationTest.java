package ai.mindconnect.agent.starter.runtime.engine;

import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.jackson.JsonMapperCustomizer;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.directory.UserHome;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;

/** The beans an application brings, and a turn through the services the starter hands out. */
class RuntimeEngineAutoConfigurationTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));
    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.ofModel("any"), List.of(), 5, Map.of());

    @TempDir
    Path dataDirectory;

    @Test
    void a_turn_runs_through_the_services_the_starter_hands_out() {
        LlmChat chat = (request, handler, cancellation, listener) ->
                handler.accept(new LlmStreamChunk.TextDelta("Hello."));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuntimeEngineAutoConfiguration.class))
                .withPropertyValues("mindconnect.data.base-dir=" + dataDirectory)
                .withBean(ScopeSupplier.class, () -> SCOPE)
                .withBean(JsonMapperCustomizer.class, () -> mapper -> mapper.addModule(new JavaTimeModule()))
                // The application's own plugin comes after the starter's: its model is the one that answers.
                .withBean(Plugin.class, () -> new Plugin() {
                    @Override
                    public String name() {
                        return "test-model";
                    }

                    @Override
                    public void install(PluginRegistry registry) {
                        registry.provide(LlmChat.class, () -> chat);
                    }
                })
                .run(context -> {
                    SessionService sessions = context.getBean(SessionService.class);
                    TurnService turns = context.getBean(TurnService.class);
                    Session session = sessions.open(new SessionSpec(UserId.of("ada"), null, HELPER, Map.of()));

                    TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Hi"));
                    Turn ended = awaitEnd(turns, turnId);

                    assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
                    assertThat(context.getBean(AgentRuntime.class).service(SessionStore.class).messages(session.id()))
                            .extracting(TurnMessage::text).containsExactly("Hi", "Hello.");
                    // The same messages, as the management API reads them.
                    assertThat(context.getBean(SessionManagement.class).messages(session.id()))
                            .extracting(managed -> managed.message().text()).containsExactly("Hi", "Hello.");
                    // An agent kept in the management is one the runtime finds by name.
                    context.getBean(AgentManagement.class).create(ManagedAgent.of(HELPER));
                    assertThat(context.getBean(AgentRuntime.class).all(AgentResolver.class).stream()
                            .flatMap(resolver -> resolver.resolve("helper", session).stream())
                            .map(AgentDefinition::name)).contains("helper");
                    // The same session, as the management API lists it.
                    assertThat(context.getBean(SessionManagement.class).list(UserId.of("ada")))
                            .extracting(listed -> listed.id()).containsExactly(session.id());
                    assertThat(dataDirectory.resolve("test/sessions/" + session.id().value() + "/runtime-session.json"))
                            .exists();
                });
    }

    @Test
    void a_deleted_session_keeps_its_directory_unless_the_installation_says_otherwise() {
        assertThat(directoryAfterDelete()).exists();
        assertThat(directoryAfterDelete("mindconnect.sessions.delete-directory=true")).doesNotExist();
    }

    /** Opens a session, puts a file into its own directory, deletes the session; the directory. */
    private Path directoryAfterDelete(String... properties) {
        Path[] directory = new Path[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuntimeEngineAutoConfiguration.class))
                .withPropertyValues("mindconnect.data.base-dir=" + dataDirectory)
                .withPropertyValues(properties)
                .withBean(ScopeSupplier.class, () -> SCOPE)
                .withBean(JsonMapperCustomizer.class, () -> mapper -> mapper.addModule(new JavaTimeModule()))
                .withBean(Plugin.class, () -> new Plugin() {
                    @Override
                    public String name() {
                        return "test-model";
                    }

                    @Override
                    public void install(PluginRegistry registry) {
                        registry.provide(LlmChat.class, () -> (request, handler, cancellation, listener) -> { });
                    }
                })
                .run(context -> {
                    Session session = context.getBean(SessionService.class)
                            .open(new SessionSpec(UserId.of("ada"), null, HELPER, Map.of()));
                    directory[0] = context.getBean(AgentRuntime.class).service(UserHome.class)
                            .sessionDirOf("ada", session.id().value()).orElseThrow();
                    java.nio.file.Files.createDirectories(directory[0]);
                    java.nio.file.Files.writeString(directory[0].resolve("notes.md"), "kept");

                    context.getBean(SessionManagement.class).delete(session.id());
                });
        return directory[0];
    }

    private static Turn awaitEnd(TurnService turns, TurnId turnId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Turn turn = turns.find(turnId).orElseThrow();
            if (turn.status() != TurnStatus.QUEUED && turn.status() != TurnStatus.RUNNING) {
                return turn;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The turn did not end");
    }
}
