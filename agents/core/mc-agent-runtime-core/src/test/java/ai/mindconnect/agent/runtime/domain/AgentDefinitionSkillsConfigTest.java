package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.agent.runtime.domain.AgentDefinition.SkillsConfig;
import ai.mindconnect.agent.runtime.domain.AgentDefinition.SkillsConfig.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skills setting is a mode — none, all, or the ones named — and its JSON
 * has to keep reading the switch-plus-names shape every stored agent had
 * before, or an upgrade would turn every agent's skills off or on at random.
 */
class AgentDefinitionSkillsConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theNewShapeRoundTrips() throws Exception {
        SkillsConfig config = SkillsConfig.specific(List.of("release", "weekly-report"));

        String json = MAPPER.writeValueAsString(config);

        assertThat(json).isEqualTo("{\"mode\":\"SPECIFIC\",\"names\":[\"release\",\"weekly-report\"]}");
        assertThat(MAPPER.readValue(json, SkillsConfig.class)).isEqualTo(config);
    }

    @Test
    void theOldShapeIsStillRead() throws Exception {
        assertThat(MAPPER.readValue("{\"enabled\":true,\"names\":[]}", SkillsConfig.class))
                .isEqualTo(SkillsConfig.ALL);
        assertThat(MAPPER.readValue("{\"enabled\":true,\"names\":[\"release\"]}", SkillsConfig.class))
                .isEqualTo(SkillsConfig.specific(List.of("release")));
        assertThat(MAPPER.readValue("{\"enabled\":false,\"names\":[\"release\"]}", SkillsConfig.class))
                .isEqualTo(SkillsConfig.NONE);
    }

    @Test
    void modeWinsOverTheOldSwitchWhenBothAreThere() throws Exception {
        assertThat(MAPPER.readValue("{\"mode\":\"NONE\",\"enabled\":true}", SkillsConfig.class))
                .isEqualTo(SkillsConfig.NONE);
    }

    @Test
    void namesCountOnlyForSpecific() {
        assertThat(new SkillsConfig(Mode.ALL, List.of("release")).names()).isEmpty();
        assertThat(new SkillsConfig(Mode.NONE, List.of("release")).names()).isEmpty();
        assertThat(new SkillsConfig(null, null)).isEqualTo(SkillsConfig.ALL);
        assertThat(SkillsConfig.specific(null).names()).isEmpty();
    }

    @Test
    void onlyNoneIsSwitchedOff() {
        assertThat(SkillsConfig.NONE.enabled()).isFalse();
        assertThat(SkillsConfig.ALL.enabled()).isTrue();
        assertThat(SkillsConfig.specific(List.of()).enabled()).isTrue();
    }

    @Test
    void anAgentWithoutTheFieldHasAllSkills() throws Exception {
        AgentDefinition def = MAPPER.readValue("{\"name\":\"n\",\"maxIterations\":5}", AgentDefinition.class);

        assertThat(def.skills()).isNull();
        assertThat(def.skillsOrDefault()).isEqualTo(SkillsConfig.ALL);
    }

    /** Nothing but the components: an extra property would change every stored agent. */
    @Test
    void serializesTheComponentsOnly() throws Exception {
        assertThat(MAPPER.writeValueAsString(SkillsConfig.ALL)).isEqualTo("{\"mode\":\"ALL\",\"names\":[]}");
    }
}
