package ai.mindconnect.agent.runtime.engine.adapter.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;

/**
 * Against a real Postgres, or skipped:
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 */
class PgSessionRepositoryTest {

    private static final Session SESSION = new Session(SessionId.of("s1"), UserId.of("ada"), null,
            new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.ofModel("small"),
                    List.of(ToolBinding.of("bash"), ToolBinding.deferred("read_pdf")), 5,
                    Map.of("memory", Map.of("strategy", "compact"))),
            Map.of("cwd", "/tmp/project"), Instant.parse("2026-11-23T10:00:00Z"));

    private Sql sql;

    @BeforeEach
    void an_empty_table() {
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
        sql.execute("DROP TABLE IF EXISTS mc_session");
    }

    private PgSessionRepository in(String namespace) {
        return new PgSessionRepository(sql, ScopeSupplier.fixed(new Namespace(namespace))).initSchema();
    }

    @Test
    void a_session_comes_back_as_it_was_written_agent_and_metadata_included() {
        PgSessionRepository sessions = in("a");

        sessions.create(SESSION);

        assertThat(sessions.find(SESSION.id())).contains(SESSION);
        assertThatThrownBy(() -> sessions.create(SESSION)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_session_is_changed_in_place() {
        PgSessionRepository sessions = in("a");
        sessions.create(SESSION);

        sessions.update(SESSION.id(), session -> session.withMetadata("title", "Pictures"));

        assertThat(sessions.find(SESSION.id()).orElseThrow().metadata())
                .containsEntry("title", "Pictures").containsEntry("cwd", "/tmp/project");
        assertThat(sessions.update(SessionId.of("unknown"), session -> session)).isEmpty();
    }

    @Test
    void sessions_are_listed_by_user_and_by_parent_and_deleted_one_at_a_time() {
        PgSessionRepository sessions = in("a");
        Session older = new Session(SessionId.of("older"), UserId.of("ada"), null, null, Map.of(),
                Instant.parse("2026-11-01T00:00:00Z"));
        Session child = new Session(SessionId.of("child"), UserId.of("ada"), SESSION.id(), null, Map.of(),
                Instant.parse("2026-11-24T00:00:00Z"));
        Session eves = new Session(SessionId.of("eves"), UserId.of("eve"), null, null, Map.of(),
                Instant.parse("2026-11-25T00:00:00Z"));
        for (Session session : List.of(older, SESSION, child, eves)) {
            sessions.create(session);
        }

        assertThat(sessions.findByUser(UserId.of("ada"))).containsExactly(SESSION, older);
        assertThat(sessions.findByParent(SESSION.id())).containsExactly(child);
        assertThat(sessions.delete(child.id())).isTrue();
        assertThat(sessions.delete(child.id())).isFalse();
        assertThat(sessions.findByParent(SESSION.id())).isEmpty();
    }

    @Test
    void the_same_id_is_another_session_in_another_namespace() {
        in("a").create(SESSION);

        assertThat(in("b").find(SESSION.id())).isEmpty();
        in("b").create(SESSION.withMetadata("title", "In b"));
        assertThat(in("a").find(SESSION.id())).contains(SESSION);
    }
}
