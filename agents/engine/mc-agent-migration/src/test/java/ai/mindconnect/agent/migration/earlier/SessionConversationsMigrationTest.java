package ai.mindconnect.agent.migration.earlier;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.jackson.IdJsonModule;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.store.DefaultSessionStore;
import ai.mindconnect.agent.runtime.store.FileSessionMessageRepository;
import ai.mindconnect.agent.runtime.store.FileSessionRepository;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jackson.JsonMapperFactory;

/**
 * Sessions of the runtime whose messages were kept as conversations: one at
 * the root that continued a conversation it names, one under it that was
 * opened with that metadata and kept its own under its id, one that kept its
 * own under its id from the start.
 */
class SessionConversationsMigrationTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("local"));
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path data;

    private final ObjectMapper json = new JsonMapperFactory(List.of(new IdJsonModule())).create()
            .registerModule(new JavaTimeModule());

    private void message(String conversation, int place, String text) throws IOException {
        Path file = data.resolve("local/conversations/" + conversation + "/messages/"
                + String.format("%010d", place) + "_" + conversation + "-" + place + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id": "%s-%d", "conversationId": "%s", "senderType": "AGENT", "type": "CHAT", "content": "%s",
                 "metadata": {}, "sequenceNum": %d, "sentAt": "2026-11-20T10:00:00Z", "turnId": "t1"}
                """.formatted(conversation, place, conversation, text, place));
    }

    @Test
    void each_session_gets_the_messages_of_its_conversation_once() throws IOException {
        FileSessionRepository sessions = new FileSessionRepository(data, json, SCOPE);
        FileSessionMessageRepository target = new FileSessionMessageRepository(data, json, SCOPE, NOW);
        Map<String, Object> continuing = Map.of(SessionConversationsMigration.CONVERSATION_ID, "c9");
        sessions.create(new Session(SessionId.of("root"), UserId.of("ada"), null, null, continuing, Instant.EPOCH));
        sessions.create(new Session(SessionId.of("called"), UserId.of("ada"), SessionId.of("root"), null, continuing,
                Instant.EPOCH));
        sessions.create(new Session(SessionId.of("plain"), UserId.of("ada"), null, null, Map.of(), Instant.EPOCH));
        message("c9", 1, "to the root");
        message("c9", 2, "and again");
        message("called", 1, "to the called");
        message("plain", 3, "to the plain one");
        SessionConversationsMigration migration = new SessionConversationsMigration(
                new FileEarlierRuntimeData(data, json, SCOPE), sessions, target, json, NOW);

        assertThat(migration.migrate()).isEqualTo("sessions: 3 moved, 0 moved before");

        DefaultSessionStore store = new DefaultSessionStore(sessions, target);
        assertThat(store.messages(SessionId.of("root"))).extracting(TurnMessage::text)
                .containsExactly("to the root", "and again");
        assertThat(store.messages(SessionId.of("called"))).extracting(TurnMessage::text)
                .containsExactly("to the called");
        assertThat(store.history(SessionId.of("plain"))).singleElement()
                .satisfies(stored -> assertThat(stored.sequence()).isEqualTo(3));
        assertThat(migration.migrate()).isEqualTo("sessions: 0 moved, 3 moved before");
    }
}
