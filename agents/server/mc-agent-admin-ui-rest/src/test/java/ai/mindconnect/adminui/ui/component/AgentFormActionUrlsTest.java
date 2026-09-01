package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent form, whose Save used to pick both verb and path with a ternary:
 * {@code isNew ? "POST" : "PUT"} next to {@code isNew ? "/agents" : "/agents/" + id}.
 * Two conditionals that had to agree, with nothing enforcing it.
 *
 * <p>Naming the handler collapses them into one — the verb now comes from
 * {@code @PostMapping} / {@code @PutMapping} and cannot drift from the path.
 * This pins down that both branches still produce what they used to.
 */
class AgentFormActionUrlsTest {

    private static final UUID AGENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Namespace NS = new Namespace("local");

    private static final LlmConfigRepository NO_CONFIGS = new LlmConfigRepository() {
        @Override public void save(LlmConfig config) { throw new UnsupportedOperationException(); }
        @Override public Optional<LlmConfig> findById(UUID id) { return Optional.empty(); }
        @Override public Optional<LlmConfig> findByName(String name) { return Optional.empty(); }
        @Override public List<LlmConfig> findAll() { return List.of(); }
        @Override public void deleteById(UUID id) { throw new UnsupportedOperationException(); }
    };

    private static final AgentDefinitionRepository NO_AGENTS = new AgentDefinitionRepository() {
        @Override public AgentDefinition save(AgentDefinition d) { throw new UnsupportedOperationException(); }
        @Override public Optional<AgentDefinition> findById(UUID id) { return Optional.empty(); }
        @Override public List<AgentDefinition> findByNamespace(Namespace ns) { return List.of(); }
        @Override public Optional<AgentDefinition> findByName(Namespace ns, String name) { return Optional.empty(); }
        @Override public void deleteById(UUID id) { throw new UnsupportedOperationException(); }
    };

    private static AgentDefinition agent() {
        return new AgentDefinition(AGENT_ID, NS, "Scout", "A test agent",
                "assistants", "bot", "prompt", null, "cfg", 5, null,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), null, null, null, null);
    }

    private static String render(AgentDefinition agent) throws Exception {
        var mapper = new ObjectMapper();
        return mapper.writeValueAsString(
                new AgentFormComponent(agent, NO_CONFIGS, NO_AGENTS, NS, mapper).render());
    }

    @Test
    void anExistingAgentIsSavedWithPutOnItsOwnUrl() throws Exception {
        String out = render(agent());

        assertThat(out).contains("\"method\":\"PUT\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID + "\"");
    }

    @Test
    void aNewAgentIsPostedToTheCollection() throws Exception {
        String out = render(null);

        assertThat(out).contains("\"method\":\"POST\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents\"");
        assertThat(out).doesNotContain("\"method\":\"PUT\"");
    }
}
