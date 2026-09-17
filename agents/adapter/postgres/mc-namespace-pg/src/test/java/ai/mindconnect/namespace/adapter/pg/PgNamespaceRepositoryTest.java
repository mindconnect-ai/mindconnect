package ai.mindconnect.namespace.adapter.pg;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PgNamespaceRepositoryTest {

    private PgNamespaceRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_namespace");
        repo = new PgNamespaceRepository(sql).initSchema();
    }

    private static final Email DAVID = Email.of("david@local");
    private static final Email ALICE = Email.of("alice@local");

    private static NamespaceDefinition acme(Email... users) {
        return new NamespaceDefinition(new Namespace("acme"), "ACME", DAVID,
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(), Set.of(users));
    }

    private static Actor who(String name) {
        return Actor.of(UserId.of(name), Email.of(name + "@local"));
    }

    @Test
    void aNamespaceSurvivesTheRoundTripAndASaveReplacesIt() {
        repo.save(acme());
        repo.save(acme(ALICE));

        assertThat(repo.findById(new Namespace("acme"))).contains(acme(ALICE));
        assertThat(repo.findAll()).hasSize(1);
    }

    @Test
    void theNamespacesVariablesSurviveTheRoundTrip() {
        NamespaceDefinition acme = acme(ALICE).withEnvironment(java.util.Map.of("OPENAI_API_KEY", "enc:abc"));
        assertThat(repo.insert(acme)).isTrue();

        assertThat(repo.findById(new Namespace("acme"))).contains(acme);
        assertThat(repo.findFor(who("alice"))).singleElement().extracting(NamespaceDefinition::environment)
                .isEqualTo(java.util.Map.of("OPENAI_API_KEY", "enc:abc"));
    }

    @Test
    void findForAnswersTheNMSide() {
        repo.save(acme(ALICE));
        repo.save(new NamespaceDefinition(new Namespace("beta"), null, ALICE,
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(), Set.of()));

        assertThat(repo.findFor(who("alice"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"), new Namespace("beta"));
        assertThat(repo.findFor(who("david"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"));
    }

    @Test
    void deleteRemovesTheRow() {
        repo.save(acme());

        assertThat(repo.deleteById(new Namespace("acme"))).isTrue();
        assertThat(repo.deleteById(new Namespace("acme"))).isFalse();
        assertThat(repo.findAll()).isEmpty();
    }
}
