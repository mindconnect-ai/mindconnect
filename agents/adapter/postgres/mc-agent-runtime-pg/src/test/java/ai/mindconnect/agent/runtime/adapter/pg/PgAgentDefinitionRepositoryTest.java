package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgAgentDefinitionRepositoryTest {

    private static final Namespace NS = new Namespace("test");

    private PgAgentDefinitionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgAgentDefinitionRepository(TestDb.fresh("mc_agent_definition"), NS).initSchema();
    }

    private static AgentDefinition def(String name) {
        return AgentDefinition.create(name, "desc", "You are " + name, "hello", "agent-default");
    }

    @Test
    void aDefinitionSurvivesTheRoundTrip() {
        AgentDefinition d = def("default-chat");
        AgentDefinition saved = repo.save(d);
        assertThat(saved).isEqualTo(d.withVersion(1L));
        assertThat(repo.findById(d.id())).contains(saved);
    }

    @Test
    void findByNameIgnoresCase() {
        AgentDefinition d = repo.save(def("Web-Researcher"));

        assertThat(repo.findByName("web-researcher")).contains(d);
        assertThat(repo.findByName("WEB-RESEARCHER")).contains(d);
        assertThat(repo.findByName("nobody")).isEmpty();
    }

    @Test
    void findAllListsByNameAndDeleteRemoves() {
        AgentDefinition b = repo.save(def("b"));
        AgentDefinition a = repo.save(def("a"));

        assertThat(repo.findAll()).containsExactly(a, b);
        repo.deleteById(a.id());
        assertThat(repo.findAll()).containsExactly(b);
    }
}
