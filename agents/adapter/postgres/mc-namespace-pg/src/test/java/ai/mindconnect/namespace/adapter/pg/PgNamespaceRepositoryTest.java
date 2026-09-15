package ai.mindconnect.namespace.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
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

    private static NamespaceDefinition acme(UserId... members) {
        return new NamespaceDefinition(new Namespace("acme"), "ACME", UserId.of("david"),
                Instant.parse("2026-09-15T12:00:00Z"), Set.of(members));
    }

    @Test
    void aNamespaceSurvivesTheRoundTripAndASaveReplacesIt() {
        repo.save(acme());
        repo.save(acme(UserId.of("alice")));

        assertThat(repo.findById(new Namespace("acme"))).contains(acme(UserId.of("alice")));
        assertThat(repo.findAll()).hasSize(1);
    }

    @Test
    void findByMemberAnswersTheNMSide() {
        repo.save(acme(UserId.of("alice")));
        repo.save(new NamespaceDefinition(new Namespace("beta"), null, UserId.of("alice"),
                Instant.parse("2026-09-15T12:00:00Z"), Set.of()));

        assertThat(repo.findByMember(UserId.of("alice"))).extracting(NamespaceDefinition::id)
                .containsExactly(new Namespace("acme"), new Namespace("beta"));
        assertThat(repo.findByMember(UserId.of("david"))).extracting(NamespaceDefinition::id)
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
