package ai.mindconnect.agent.runtime.store;

import static org.assertj.core.api.Assertions.assertThat;

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
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.message.adapter.file.FileMessageRepository;

/**
 * Sessions kept as conversations of the message repository, moved into a
 * repository of their own on files: what the new store reads is what the old
 * one read, place and time included.
 */
class SessionMessageMigrationTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));
    private static final JsonMapperFactory MAPPERS = new JsonMapperFactory(List.of(new IdJsonModule()));
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private final ObjectMapper json = MAPPERS.create().registerModule(new JavaTimeModule());

    private Session session(String id, String parent) {
        return new Session(SessionId.of(id), UserId.of("ada"), parent == null ? null : SessionId.of(parent), null,
                Map.of(), Instant.parse("2026-11-25T10:00:00Z"));
    }

    @Test
    void every_session_reads_the_same_after_the_move_and_a_second_run_moves_nothing() {
        FileSessionRepository sessions = new FileSessionRepository(directory, json, SCOPE);
        SessionStore legacy = new RepositorySessionStore(sessions, new FileMessageRepository(directory, json, SCOPE),
                MAPPERS);
        FileSessionMessageRepository target = new FileSessionMessageRepository(directory, json, SCOPE, NOW);
        legacy.create(session("s1", null));
        legacy.create(session("s2", "s1"));
        legacy.create(session("empty", null));
        legacy.append(SessionId.of("s1"), List.of(
                TurnMessage.user(List.of(new ContentPart.Text("What is there?"))).inTurn("t1"),
                TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "bash", Map.of("command", "ls"))))
                        .inTurn("t1"),
                TurnMessage.toolResult("c1", "bash", "a.png", false).inTurn("t1"),
                TurnMessage.assistant("A picture.").inTurn("t1")));
        legacy.append(SessionId.of("s2"), List.of(TurnMessage.assistant("from below")));
        // The first message removed: the places that are left do not start at one.
        legacy.deleteMessages(SessionId.of("s1"), 1, 1);
        SessionMessageMigration migration = new SessionMessageMigration(sessions, legacy, target, NOW);

        SessionMessageMigration.Report first = migration.run();

        assertThat(first.migrated()).extracting(SessionId::value).containsExactlyInAnyOrder("s1", "s2", "empty");
        SessionStore moved = new DefaultSessionStore(sessions, target);
        for (String id : List.of("s1", "s2", "empty")) {
            assertThat(moved.history(SessionId.of(id))).isEqualTo(legacy.history(SessionId.of(id)));
        }
        assertThat(moved.find(SessionId.of("s1")).orElseThrow().metadata())
                .containsEntry(SessionMessageMigration.MIGRATED_AT, "2026-11-25T12:00:00Z");

        SessionMessageMigration.Report second = migration.run();

        assertThat(second.migrated()).isEmpty();
        assertThat(second.skipped()).extracting(SessionId::value).containsExactlyInAnyOrder("s1", "s2", "empty");
    }

    @Test
    void a_message_appended_after_the_move_takes_a_place_above_the_moved_ones() {
        FileSessionRepository sessions = new FileSessionRepository(directory, json, SCOPE);
        SessionStore legacy = new RepositorySessionStore(sessions, new FileMessageRepository(directory, json, SCOPE),
                MAPPERS);
        FileSessionMessageRepository target = new FileSessionMessageRepository(directory, json, SCOPE, NOW);
        legacy.create(session("s1", null));
        legacy.append(SessionId.of("s1"), List.of(TurnMessage.assistant("one"), TurnMessage.assistant("two")));
        legacy.deleteMessages(SessionId.of("s1"), 2, 2);
        new SessionMessageMigration(sessions, legacy, target, NOW).run();

        new DefaultSessionStore(sessions, target).append(SessionId.of("s1"), List.of(TurnMessage.assistant("three")));

        assertThat(target.findBySession(SessionId.of("s1"))).extracting(stored -> stored.sequence())
                .containsExactly(1L, 2L);
    }
}
