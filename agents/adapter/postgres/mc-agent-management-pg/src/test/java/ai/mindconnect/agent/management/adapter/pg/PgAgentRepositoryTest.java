package ai.mindconnect.agent.management.adapter.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import ai.mindconnect.agent.management.RepositoryAgentManagement;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;

/**
 * Against a real Postgres, or skipped:
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 */
class PgAgentRepositoryTest {

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
        sql.execute("DROP TABLE IF EXISTS mc_managed_agent");
    }

    private AgentManagement in(String namespace) {
        return new RepositoryAgentManagement(
                new PgAgentRepository(sql, ScopeSupplier.fixed(new Namespace(namespace))).initSchema());
    }

    private static AgentDefinition definition(String name) {
        return new AgentDefinition(name, "Looks things up.", "You research.", ModelSettings.ofModel("small"),
                List.of(new ToolBinding("web_search", false, Map.of("needsApproval", true))), 6,
                Map.of("subAgents", List.of("fact-checker")));
    }

    @Test
    void an_agent_comes_back_as_it_was_stored_and_is_changed_under_its_version() {
        AgentManagement agents = in("a");
        ManagedAgent created = agents.create(
                ManagedAgent.of(definition("researcher")).shownAs("assistants", "telescope", "What shall I look up?"));

        assertThat(agents.find(created.id()).orElseThrow().definition()).isEqualTo(definition("researcher"));
        assertThat(agents.find(created.id()).orElseThrow().group()).isEqualTo("assistants");

        ManagedAgent renamed = agents.update(created.id(), 1, agent -> agent.withDefinition(definition("scout")));

        assertThat(renamed.version()).isEqualTo(2);
        assertThat(agents.findByName("scout")).isPresent();
        assertThatThrownBy(() -> agents.update(created.id(), 1, agent -> agent))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    void agents_are_listed_by_name_within_their_namespace_and_deleted() {
        AgentManagement a = in("a");
        ManagedAgent writer = a.create(ManagedAgent.of(definition("writer")));
        a.create(ManagedAgent.of(definition("researcher")));
        in("b").create(ManagedAgent.of(definition("elsewhere")));

        assertThat(a.list()).extracting(agent -> agent.definition().name()).containsExactly("researcher", "writer");

        a.delete(writer.id());

        assertThat(a.list()).extracting(agent -> agent.definition().name()).containsExactly("researcher");
        assertThat(in("b").list()).hasSize(1);
    }
}
