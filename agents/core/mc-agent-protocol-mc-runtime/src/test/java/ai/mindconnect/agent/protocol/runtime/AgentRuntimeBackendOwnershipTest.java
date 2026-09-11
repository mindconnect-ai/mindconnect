package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The backend acts for its caller: another user's session, response or file
 * is not found, the same answer a missing one gets. No model answers here —
 * the turns fail against an address nothing listens on, which leaves the
 * responses in place to be asked about.
 */
class AgentRuntimeBackendOwnershipTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private AgentRuntime runtime;
    private final AtomicReference<UserId> caller = new AtomicReference<>(ALICE);
    private AgentRuntimeBackend backend;

    @BeforeEach
    void setUp() {
        runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(new LlmConfig(LlmConfigId.random(), "chat", LlmProvider.LM_STUDIO, "none",
                        "http://127.0.0.1:9", "none", 0.2, 256, Map.of(), 8_000,
                        false, null, null, null, LlmConfigType.CHAT, null))
                .defaultLlmConfigName("chat")
                .build();
        runtime.agentDefinitions().save(AgentDefinition.create("helper", "Helps.", "You help.", null, "chat"));
        backend = new AgentRuntimeBackend(runtime.chatService(), runtime.sessionService(),
                runtime.agentDefinitions(), runtime.conversationManager(), caller::get)
                .withFiles(runtime.fileStore(), runtime::attachStored);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void aSessionOpensForTheCurrentCaller() {
        caller.set(BOB);

        Session session = backend.open("helper");

        assertThat(runtime.sessionService().findSession(SessionId.of(session.id())).userId()).isEqualTo(BOB);
    }

    @Test
    void anotherUsersSessionResponseAndFileAreNotFound() {
        Session alices = backend.open("helper");
        Response response = backend.responses().create(new ResponseRequest(alices.id(),
                List.of(ConversationItem.Message.user("Hello")), true, List.of()));
        StoredFile alicesFile = backend.files().upload("notes.txt", "text/plain",
                "private".getBytes(StandardCharsets.UTF_8));

        caller.set(BOB);
        assertThat(backend.sessions().get(alices.id())).isEmpty();
        assertThat(backend.responses().get(response.id())).isEmpty();
        assertThat(backend.responses().cancel(response.id())).isFalse();
        assertThatThrownBy(() -> backend.responses().subscribe(SubscribeRequest.replay(response.id()), e -> { }))
                .isInstanceOf(RuntimeBackendException.class);
        assertThatThrownBy(() -> backend.responses().create(new ResponseRequest(alices.id(),
                List.of(ConversationItem.Message.user("Me too")), true, List.of())))
                .isInstanceOf(RuntimeBackendException.class);
        assertThat(backend.files().get(alicesFile.id())).isEmpty();

        caller.set(ALICE);
        assertThat(backend.sessions().get(alices.id())).isPresent();
        assertThat(backend.responses().get(response.id())).isPresent();
        assertThat(backend.files().get(alicesFile.id())).isPresent();
    }

    @Test
    void aBoundViewKeepsActingForItsUserWhateverTheSupplierSays() {
        Session alices = backend.open("helper");
        Response response = backend.responses().create(new ResponseRequest(alices.id(),
                List.of(ConversationItem.Message.user("Hello")), true, List.of()));
        AgentRuntimeBackend alicesView = backend.actingFor(backend.currentUser());

        caller.set(BOB);

        assertThat(alicesView.responses().get(response.id())).isPresent();
        assertThat(backend.responses().get(response.id())).isEmpty();
    }

    @Test
    void aFileWithoutCreatorStaysReadableById() throws Exception {
        var old = runtime.fileStore().save("old.txt", "text/plain",
                new ByteArrayInputStream("old".getBytes(StandardCharsets.UTF_8)));

        caller.set(BOB);

        assertThat(backend.files().get(old.id().value())).isPresent();
    }
}
