package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgAgentDefinitionRepositoryTest {

    private static final Namespace NS = new Namespace("default");

    private PgAgentDefinitionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgAgentDefinitionRepository(TestDb.fresh("mc_agent_definition")).initSchema();
    }

    private static AgentDefinition def(Namespace ns, String name) {
        return AgentDefinition.create(ns, name, "desc", "You are " + name, "hello", "agent-default");
    }

    @Test
    void aDefinitionSurvivesTheRoundTrip() {
        AgentDefinition d = def(NS, "default-chat");
        repo.save(d);
        assertThat(repo.findById(d.id())).contains(d);
    }

    @Test
    void findByNameIgnoresCaseAndStaysInTheNamespace() {
        AgentDefinition d = def(NS, "Web-Researcher");
        repo.save(d);
        repo.save(def(new Namespace("other"), "web-researcher"));

        assertThat(repo.findByName(NS, "web-researcher")).contains(d);
        assertThat(repo.findByName(NS, "WEB-RESEARCHER")).contains(d);
        assertThat(repo.findByName(new Namespace("nobody"), "web-researcher")).isEmpty();
    }

    @Test
    void findByNamespaceListsByNameAndDeleteRemoves() {
        AgentDefinition b = def(NS, "b");
        AgentDefinition a = def(NS, "a");
        repo.save(b);
        repo.save(a);
        repo.save(def(new Namespace("other"), "c"));

        assertThat(repo.findByNamespace(NS)).containsExactly(a, b);
        repo.deleteById(a.id());
        assertThat(repo.findByNamespace(NS)).containsExactly(b);
    }
}
