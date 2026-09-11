package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PgUserRepositoryTest {

    private static final Namespace NS = new Namespace("test");

    private Sql sql;
    private PgUserRepository repo;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_user");
        repo = new PgUserRepository(sql, NS).initSchema();
    }

    private static User user(String id) {
        Instant at = Instant.parse("2026-09-11T12:00:00Z");
        return new User(UserId.of(id), "sub-" + id, "https://auth/realms/mc", "Name " + id, id + "@example.com", at, at);
    }

    @Test
    void aUserSurvivesTheRoundTripAndASaveReplacesIt() {
        User alice = user("Alice.Smith@example.com");
        repo.save(alice);
        assertThat(repo.findById(alice.id())).contains(alice);

        User renamed = new User(alice.id(), alice.subject(), alice.issuer(), "Alice", alice.email(),
                alice.createdAt(), alice.lastLoginAt().plusSeconds(600));
        repo.save(renamed);
        assertThat(repo.findById(alice.id())).contains(renamed);
        assertThat(repo.findAll()).containsExactly(renamed);
    }

    @Test
    void aRepositoryBoundToAnotherNamespaceSeesNothing() {
        repo.save(user("alice"));
        var other = new PgUserRepository(sql, new Namespace("other")).initSchema();

        assertThat(other.findById(UserId.of("alice"))).isEmpty();
        assertThat(other.findAll()).isEmpty();
    }

    @Test
    void initSchemaIsIdempotent() {
        repo.save(user("alice"));
        new PgUserRepository(Sql.of(TestDb.require()), NS).initSchema();
        assertThat(repo.findAll()).hasSize(1);
    }
}
