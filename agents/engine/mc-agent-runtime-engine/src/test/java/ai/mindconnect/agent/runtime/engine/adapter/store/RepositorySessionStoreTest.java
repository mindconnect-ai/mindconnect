package ai.mindconnect.agent.runtime.engine.adapter.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.adapter.file.FileMessageRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.out.MessageRepository;

/** The same store over both kinds of repository: what goes in comes back as it was. */
class RepositorySessionStoreTest {

    private static final NamespaceProvider NAMESPACES = NamespaceProvider.fixed("test");
    private static final SessionId ID = SessionId.of("s1");
    private static final Session SESSION = new Session(ID, UserId.of("ada"), null,
            new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.ofModel("small"),
                    List.of(ToolBinding.of("bash"), ToolBinding.deferred("read_pdf")), 5,
                    Map.of("memory", Map.of("strategy", "compact"))),
            Map.of("cwd", "/tmp/project"), Instant.parse("2026-11-23T10:00:00Z"));

    @TempDir
    static Path directory;

    static Stream<SessionStore> stores() {
        ScopeSupplier scope = RepositorySessionStore.scopeOf(NAMESPACES);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        return Stream.of(
                new RepositorySessionStore(new InMemorySessionRepository(NAMESPACES),
                        new InMemoryMessageRepository(scope)),
                new RepositorySessionStore(new FileSessionRepository(directory, json, NAMESPACES),
                        new FileMessageRepository(directory, json, scope)));
    }

    @ParameterizedTest
    @MethodSource("stores")
    void a_session_and_its_messages_come_back_as_they_were_written(SessionStore store) {
        store.create(SESSION);
        List<TurnMessage> written = List.of(
                TurnMessage.user(List.of(new ContentPart.Text("What is on this?"),
                        ContentPart.Image.of(new ContentPart.MediaSource.Url("https://example.org/a.png"))))
                        .inTurn("t1"),
                TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "bash", Map.of("command", "ls"))))
                        .inTurn("t1"),
                TurnMessage.toolResult("c1", "bash", "a.png", false).inTurn("t1"),
                TurnMessage.assistant("A picture.").inTurn("t1"));

        List<TurnMessage> stored = store.append(ID, written);

        assertThat(store.find(ID)).contains(SESSION);
        assertThat(stored).extracting(TurnMessage::id).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(store.messages(ID)).isEqualTo(stored);
        assertThat(stored).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id").isEqualTo(written);
    }

    @Test
    void a_sub_agents_session_keeps_its_messages_out_of_the_conversation_its_caller_continues() {
        Session caller = new Session(SessionId.of("s-caller"), UserId.of("ada"), null, null,
                Map.of(RepositorySessionStore.CONVERSATION_ID, "c9"), Instant.EPOCH);
        // Opened with the caller's metadata, as the sub-agent tool does.
        Session called = new Session(SessionId.of("s-called"), UserId.of("ada"), caller.id(), null,
                caller.metadata(), Instant.EPOCH);

        assertThat(RepositorySessionStore.conversationOf(caller).value()).isEqualTo("c9");
        assertThat(RepositorySessionStore.conversationOf(called).value()).isEqualTo("s-called");
    }

    @Test
    void messages_are_stored_the_way_the_message_repository_has_always_held_a_conversation() {
        MessageRepository messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
        SessionStore store = new RepositorySessionStore(new InMemorySessionRepository(NAMESPACES), messages);
        // A session that continues a conversation begun before it: the conversation's id is not its own.
        Session session = new Session(SessionId.of("s3"), UserId.of("ada"), null, null,
                Map.of(RepositorySessionStore.CONVERSATION_ID, "c9"), Instant.EPOCH);
        store.create(session);
        ConversationId conversation = ConversationId.of("c9");
        // What the earlier runtime left there: a result as JSON, paired to its call by metadata.
        messages.append(conversation, sequence -> Message.of(conversation, "ada", ParticipantType.USER,
                MessageType.CHAT, "An earlier question", sequence));

        store.append(session.id(), List.of(
                TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "bash", Map.of("command", "ls")))),
                TurnMessage.toolResult("c1", "bash", "a.png", false)));

        List<Message> stored = messages.findByConversation(conversation, new PageRequest(0, 10));
        assertThat(stored).extracting(Message::type)
                .containsExactly(MessageType.CHAT, MessageType.TOOL_CALL, MessageType.TOOL_RESULT);
        assertThat(stored.get(1).content())
                .isEqualTo("{\"toolCalls\":[{\"id\":\"c1\",\"name\":\"bash\",\"arguments\":{\"command\":\"ls\"}}]}");
        assertThat(stored.get(1).metadata()).containsEntry("callIds", List.of("c1"));
        assertThat(stored.get(2).content()).contains("\"toolCallId\":\"c1\"", "\"result\":\"a.png\"");
        assertThat(store.messages(session.id())).extracting(TurnMessage::text)
                .containsExactly("An earlier question", "", "a.png");
    }

    @ParameterizedTest
    @MethodSource("stores")
    void a_message_shown_shortened_keeps_what_it_said_and_says_what_is_shown_instead(SessionStore store) {
        SessionId id = SessionId.of("s2");
        store.create(new Session(id, UserId.of("ada"), null, null, Map.of(), Instant.EPOCH));
        TurnMessage result = store.append(id, List.of(TurnMessage.toolResult("c1", "bash", "a long listing", false)))
                .get(0);

        store.updateMessageMetadata(id, result.id(), metadata -> {
            Map<String, Object> changed = new java.util.HashMap<>(metadata);
            changed.put(SessionStore.COMPRESSED, "[removed]");
            return changed;
        });

        TurnMessage read = store.messages(id).get(0);
        assertThat(read.text()).isEqualTo("a long listing");
        assertThat(read.metadata()).containsEntry(SessionStore.COMPRESSED, "[removed]").containsEntry("callId", "c1");
    }

    @ParameterizedTest
    @MethodSource("stores")
    void a_session_is_changed_in_place_and_created_only_once(SessionStore store) {
        if (store.find(ID).isEmpty()) {
            store.create(SESSION);
        }

        store.update(ID, session -> session.withMetadata("title", "Pictures"));

        assertThat(store.find(ID).orElseThrow().metadata()).containsEntry("title", "Pictures")
                .containsEntry("cwd", "/tmp/project");
        assertThatThrownBy(() -> store.create(SESSION)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_message_written_the_earlier_way_reads_with_its_media_and_the_call_its_result_answers() {
        InMemoryMessageRepository messages = new InMemoryMessageRepository(RepositorySessionStore.scopeOf(NAMESPACES));
        SessionStore store = new RepositorySessionStore(new InMemorySessionRepository(NAMESPACES), messages);
        store.create(SESSION);
        ai.mindconnect.message.domain.ConversationId conversation = RepositorySessionStore.conversationOf(SESSION);
        messages.append(conversation, seq -> ai.mindconnect.message.domain.Message.of(conversation, "ada",
                ai.mindconnect.message.domain.ParticipantType.USER, ai.mindconnect.message.domain.MessageType.CHAT,
                List.of(new ai.mindconnect.message.domain.ContentPart.Text("What is this?"),
                        new ai.mindconnect.message.domain.ContentPart.Image("f-1", "owl.png", "image/png", 3)), seq));
        messages.append(conversation, seq -> ai.mindconnect.message.domain.Message.of(conversation, "helper",
                ai.mindconnect.message.domain.ParticipantType.AGENT,
                ai.mindconnect.message.domain.MessageType.TOOL_RESULT,
                "{\"toolCallId\":\"call_1\",\"toolName\":\"bash\",\"result\":\"done\"}", seq));

        List<TurnMessage> read = store.messages(ID);

        assertThat(read.get(0).content()).containsExactly(new ContentPart.Text("What is this?"),
                new ContentPart.Image(new ContentPart.MediaSource.FileId("f-1"), ContentPart.Image.Detail.AUTO,
                        "owl.png"));
        assertThat(read.get(1).text()).isEqualTo("done");
        assertThat(read.get(1).metadata()).containsEntry("callId", "call_1").containsEntry("toolName", "bash");
    }
}
