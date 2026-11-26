package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    private static final AgentId AGENT_ID = AgentId.of("11111111-2222-3333-4444-555555555555");

    private static final LlmConfigRepository NO_CONFIGS = new LlmConfigRepository() {
        @Override public void save(LlmConfig config) { throw new UnsupportedOperationException(); }
        @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
        @Override public Optional<LlmConfig> findByName(String name) { return Optional.empty(); }
        @Override public List<LlmConfig> findAll() { return List.of(); }
        @Override public void deleteById(LlmConfigId id) { throw new UnsupportedOperationException(); }
    };

    private static ManagedAgent agent() {
        return new ManagedAgent(AGENT_ID,
                new ai.mindconnect.agent.runtime.api.domain.AgentDefinition("Scout", "A test agent", "prompt",
                        ai.mindconnect.agent.runtime.api.domain.ModelSettings.ofModel("cfg"), List.of(), 5,
                        java.util.Map.of()),
                "assistants", "bot", null, ManagedAgent.Status.ACTIVE, null, null, 0);
    }

    private static String render(ManagedAgent agent) throws Exception {
        var mapper = new ObjectMapper();
        return mapper.writeValueAsString(
                new AgentFormComponent(agent, NO_CONFIGS, List.of(), mapper).render());
    }

    @Test
    void anExistingAgentIsSavedWithPutOnItsOwnUrl() throws Exception {
        String out = render(agent());

        assertThat(out).contains("\"method\":\"PUT\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents/" + AGENT_ID.value() + "\"");
    }

    @Test
    void aNewAgentIsPostedToTheCollection() throws Exception {
        String out = render(null);

        assertThat(out).contains("\"method\":\"POST\"");
        assertThat(out).contains("\"url\":\"/admin/api/agents\"");
        assertThat(out).doesNotContain("\"method\":\"PUT\"");
    }
}
