package ai.mindconnect.agent.starter.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.out.MessageRepository;
import ai.mindconnect.common.PageRequest;
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
    void a_turn_runs_on_the_applications_queue_and_writes_into_its_message_repository() {
        LlmChat chat = (request, handler, cancellation, listener) ->
                handler.accept(new LlmStreamChunk.TextDelta("Hello."));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RuntimeEngineAutoConfiguration.class))
                .withPropertyValues("mindconnect.data.base-dir=" + dataDirectory)
                .withBean(ScopeSupplier.class, () -> SCOPE)
                .withBean(ObjectMapper.class, () -> new ObjectMapper().registerModule(new JavaTimeModule()))
                .withBean(TaskQueue.class, () -> new LocalTaskQueue(new InMemoryTaskStore()))
                .withBean(MessageRepository.class, () -> new InMemoryMessageRepository(SCOPE))
                .withBean(LlmChat.class, () -> chat)
                .withBean(LlmConfigRepository.class, () -> mock(LlmConfigRepository.class))
                .run(context -> {
                    SessionService sessions = context.getBean(SessionService.class);
                    TurnService turns = context.getBean(TurnService.class);
                    Session session = sessions.open(new SessionSpec(UserId.of("ada"), null, HELPER, Map.of()));

                    TurnId turnId = turns.submit(TurnRequest.text(session.id(), "Hi"));
                    Turn ended = awaitEnd(turns, turnId);

                    assertThat(ended.status()).isEqualTo(TurnStatus.COMPLETED);
                    assertThat(context.getBean(SessionStore.class).messages(session.id()))
                            .extracting(TurnMessage::text).containsExactly("Hi", "Hello.");
                    // The same conversation the application's own code reads.
                    assertThat(context.getBean(MessageRepository.class)
                            .findByConversation(ConversationId.of(session.id().value()), new PageRequest(0, 10)))
                            .extracting(Message::content).containsExactly("Hi", "Hello.");
                    // An agent kept in the management is one the runtime finds by name.
                    context.getBean(AgentManagement.class).create(ManagedAgent.of(HELPER));
                    assertThat(context.getBean(PluginRegistry.class).all(AgentResolver.class).get(0)
                            .resolve("helper", session)).contains(HELPER);
                    // The same session, as the management API lists it.
                    assertThat(context.getBean(SessionManagement.class).list(UserId.of("ada")))
                            .extracting(listed -> listed.id()).containsExactly(session.id());
                    assertThat(dataDirectory.resolve("test/sessions/" + session.id().value() + "/runtime-session.json"))
                            .exists();
                });
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
