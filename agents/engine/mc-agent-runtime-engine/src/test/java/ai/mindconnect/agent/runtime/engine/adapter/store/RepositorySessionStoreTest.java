package ai.mindconnect.agent.runtime.engine.adapter.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
        return Stream.of(
                RepositorySessionStore.inMemory(NAMESPACES),
                RepositorySessionStore.onFiles(directory, new ObjectMapper().registerModule(new JavaTimeModule()),
                        NAMESPACES));
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
}
