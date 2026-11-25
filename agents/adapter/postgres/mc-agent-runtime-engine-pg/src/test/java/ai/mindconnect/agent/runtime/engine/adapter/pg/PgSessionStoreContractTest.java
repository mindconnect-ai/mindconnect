package ai.mindconnect.agent.runtime.engine.adapter.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.DefaultSessionStore;
import ai.mindconnect.agent.runtime.store.SessionStoreContract;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;

/**
 * The store that keeps a session's messages in a table of their own, against a
 * real Postgres, or skipped:
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 */
class PgSessionStoreContractTest extends SessionStoreContract {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));

    private Sql sql;

    @BeforeEach
    void empty_tables() {
        PGSimpleDataSource database = new PGSimpleDataSource();
        database.setUrl(System.getenv().getOrDefault("MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres"));
        database.setUser(System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres"));
        database.setPassword(System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test"));
        try (Connection connection = database.getConnection()) {
            assumeTrue(connection.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable — skipping");
        }
        sql = Sql.of(database);
        sql.execute("DROP TABLE IF EXISTS mc_session, mc_session_message, mc_session_message_seq");
    }

    @Override
    protected SessionStore store() {
        return new DefaultSessionStore(new PgSessionRepository(sql, SCOPE).initSchema(),
                new PgSessionMessageRepository(sql, SCOPE, Clock.systemUTC()).initSchema());
    }

    @Test
    void appends_to_one_session_at_the_same_time_get_different_places() throws Exception {
        SessionStore store = store();
        SessionId id = SessionId.of("s1");
        store.create(new Session(id, UserId.of("ada"), null, null, Map.of(), Instant.parse("2026-11-25T10:00:00Z")));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> appends = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                appends.add(pool.submit(() -> store.append(id,
                        List.of(TurnMessage.assistant("one"), TurnMessage.assistant("two")))));
            }
            for (Future<?> append : appends) {
                append.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(store.history(id)).hasSize(40).extracting(StoredMessage::sequence).doesNotHaveDuplicates();
    }

    @Test
    void restored_messages_keep_place_and_time_and_the_session_carries_on_above_them() {
        PgSessionRepository sessions = new PgSessionRepository(sql, SCOPE).initSchema();
        PgSessionMessageRepository messages = new PgSessionMessageRepository(sql, SCOPE, Clock.systemUTC()).initSchema();
        SessionId id = SessionId.of("s1");
        sessions.create(new Session(id, UserId.of("ada"), null, null, Map.of(), Instant.parse("2026-11-25T10:00:00Z")));
        List<StoredMessage> before = List.of(
                new StoredMessage(TurnMessage.assistant("three").stored("m3"), 3, Instant.parse("2026-11-01T10:00:00Z")),
                new StoredMessage(TurnMessage.assistant("five").stored("m5"), 5, Instant.parse("2026-11-01T10:05:00Z")));

        messages.restore(id, before);
        messages.append(id, List.of(TurnMessage.assistant("six")));

        assertThat(messages.findBySession(id).subList(0, 2)).isEqualTo(before);
        assertThat(messages.findBySession(id)).extracting(StoredMessage::sequence).containsExactly(3L, 5L, 6L);
        assertThat(sessions.findAll()).extracting(Session::id).containsExactly(id);
    }

    @Test
    void a_nul_character_postgres_cannot_keep_is_stored_as_the_replacement_character() {
        SessionStore store = store();
        SessionId id = SessionId.of("s1");
        store.create(new Session(id, UserId.of("ada"), null, null, Map.of(), Instant.parse("2026-11-25T10:00:00Z")));

        store.append(id, List.of(TurnMessage.toolResult("c1", "read_file", "PK\u0000\u0003", false)));

        assertThat(store.messages(id)).singleElement().extracting(TurnMessage::text).isEqualTo("PK\uFFFD\u0003");
    }
}
