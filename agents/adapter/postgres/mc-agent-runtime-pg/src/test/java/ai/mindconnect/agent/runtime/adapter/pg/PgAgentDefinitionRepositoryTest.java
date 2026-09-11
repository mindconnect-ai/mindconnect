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
        repo.save(d);
        assertThat(repo.findById(d.id())).contains(d);
    }

    @Test
    void findByNameIgnoresCase() {
        AgentDefinition d = def("Web-Researcher");
        repo.save(d);

        assertThat(repo.findByName("web-researcher")).contains(d);
        assertThat(repo.findByName("WEB-RESEARCHER")).contains(d);
        assertThat(repo.findByName("nobody")).isEmpty();
    }

    @Test
    void findAllListsByNameAndDeleteRemoves() {
        AgentDefinition b = def("b");
        AgentDefinition a = def("a");
        repo.save(b);
        repo.save(a);

        assertThat(repo.findAll()).containsExactly(a, b);
        repo.deleteById(a.id());
        assertThat(repo.findAll()).containsExactly(b);
    }
}
