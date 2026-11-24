package ai.mindconnect.agent.protocol.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;

/**
 * A session, a response and a file are the caller's: for anybody else they
 * are not there.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AgentRuntimeBackendOwnershipTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    @TempDir
    Path dir;

    private final AtomicReference<UserId> caller = new AtomicReference<>(ALICE);
    private BackendFixture fixture;
    private FileStore files;
    private AgentRuntimeBackend backend;

    @BeforeEach
    void setUp() {
        fixture = new BackendFixture(List.of());
        fixture.agent(new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.ofModel("any"),
                List.of(), 5, Map.of()));
        fixture.says(TurnMessage.assistant("Hello."), TurnMessage.assistant("Hello again."));
        files = new FilesystemFileStore(dir, ScopeSupplier.fixed(new Namespace("test")));
        backend = fixture.backend(caller::get).withFiles(files, (sessionId, stored) -> "attached");
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aSessionOpensForTheCurrentCaller() {
        caller.set(BOB);

        Session session = backend.open("helper");

        assertThat(fixture.runtime().service(SessionManagement.class)
                .find(ai.mindconnect.agent.SessionId.of(session.id())).orElseThrow()
                .userId().value()).isEqualTo("bob");
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
        assertThat(backend.conversations().get(alices.conversationId())).isEmpty();
        assertThat(backend.conversations().items(alices.conversationId(), 0, 10)).isEmpty();
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
        assertThat(backend.conversations().get(alices.conversationId())).isPresent();
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
        var old = files.save("old.txt", "text/plain",
                new ByteArrayInputStream("old".getBytes(StandardCharsets.UTF_8)));

        caller.set(BOB);

        assertThat(backend.files().get(old.id().value())).isPresent();
    }

    /** The answer of a turn is what the conversation holds afterwards, in the caller's own words and the agent's. */
    @Test
    void aTurnAnswersAndItsMessagesAreTheConversationsItems() {
        Session session = backend.open("helper");

        Response response = backend.create(ResponseRequest.text(session.id(), "Hi"));

        assertThat(response.outputText()).isEqualTo("Hello.");
        assertThat(response.usage().totalTokens()).isEqualTo(11);
        assertThat(backend.conversations().items(session.conversationId(), 0, 10))
                .extracting(entry -> ((ConversationItem.Message) entry.item()).content().get(0))
                .extracting(part -> ((ai.mindconnect.agent.protocol.item.ContentPart.Text) part).text())
                .containsExactly("Hi", "Hello.");
    }
}
