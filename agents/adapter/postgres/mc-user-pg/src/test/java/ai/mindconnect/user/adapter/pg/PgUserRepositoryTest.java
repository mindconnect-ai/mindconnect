package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PgUserRepositoryTest {


    private Sql sql;
    private PgUserRepository repo;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_user");
        repo = new PgUserRepository(sql).initSchema();
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
    void theUsersVariablesSurviveTheRoundTrip() {
        User alice = user("alice").withEnvironment(java.util.Map.of("OPENAI_API_KEY", "enc:abc", "TAVILY_API_KEY", "enc:def"));
        repo.save(alice);

        assertThat(repo.findById(alice.id())).contains(alice);
        assertThat(repo.findById(alice.id())).get().extracting(User::environment)
                .isEqualTo(java.util.Map.of("OPENAI_API_KEY", "enc:abc", "TAVILY_API_KEY", "enc:def"));

        repo.save(alice.withEnvironment(java.util.Map.of()));
        assertThat(repo.findById(alice.id())).get().extracting(User::environment).isEqualTo(java.util.Map.of());
    }

    @Test
    void theTimeZoneSurvivesTheRoundTrip() {
        User alice = user("alice").withTimeZone("America/New_York");
        repo.save(alice);

        assertThat(repo.findById(alice.id())).contains(alice);
        assertThat(repo.findById(alice.id())).get().extracting(User::timeZone).isEqualTo("America/New_York");

        repo.save(alice.withTimeZone(null));
        assertThat(repo.findById(alice.id())).get().extracting(User::timeZone).isNull();
    }

    @Test
    void initSchemaIsIdempotent() {
        repo.save(user("alice"));
        new PgUserRepository(Sql.of(TestDb.require())).initSchema();
        assertThat(repo.findAll()).hasSize(1);
    }
}
