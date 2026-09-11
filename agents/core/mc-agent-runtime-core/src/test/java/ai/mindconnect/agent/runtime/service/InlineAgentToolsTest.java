package ai.mindconnect.agent.runtime.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code run_agent} tells the model about who it can call. The names
 * used to be baked into the description — three of them, always the same —
 * so an agent whose roster held one of them was told about two it could not
 * reach, and a project's own agents were never mentioned at all. A model
 * reads a tool description far more reliably than it calls list_agents, so
 * this is the sentence that has to be true, including about how complete it
 * is.
 */
class InlineAgentToolsTest {

    @Test
    void aRosterMakesTheListComplete_soItSaysAvailableToYou() {
        var def = InlineAgentTools.runAgentDefinition(
                List.of("verifier", "api-reviewer"), List.of("explorer", "verifier"));

        assertThat(def.name()).isEqualTo(InlineAgentTools.RUN_AGENT);
        assertThat(def.description())
                .contains("Available to you: verifier, api-reviewer, explorer.")
                .as("the roster's copy of a project name is not repeated").doesNotContain("verifier, verifier")
                .as("still says what the tool is for").contains("Delegates a task to a specialist agent");
        assertThat(def.parametersSchema())
                .isEqualTo(InlineAgentTools.runAgentDefinition().parametersSchema());
    }

    @Test
    void withoutARosterTheRegistryIsNotHidden() {
        // An empty roster means the agent may call everything, which cannot
        // be enumerated here. Naming only the project's would read as the
        // whole truth and stop the model reaching for the registry.
        var def = InlineAgentTools.runAgentDefinition(List.of("verifier", "test-writer"), List.of());

        assertThat(def.description())
                .contains("This project defines: verifier, test-writer.")
                .contains("Call list_agents for the ones registered on the server.")
                .as("never claims to be the complete set").doesNotContain("Available to you");
    }

    @Test
    void namingNobodyPointsAtListAgentsInstead() {
        var noNames = InlineAgentTools.runAgentDefinition(List.of(), List.of());

        assertThat(noNames.description()).isEqualTo(InlineAgentTools.runAgentDefinition().description());
        assertThat(noNames.description()).contains("Call list_agents");
        assertThat(InlineAgentTools.runAgentDefinition(null, null).description())
                .isEqualTo(noNames.description());
    }

    @Test
    void noAgentIsNamedThatNobodyPromised() {
        String plain = InlineAgentTools.runAgentDefinition().description();

        assertThat(plain)
                .as("the old description invented a roster")
                .doesNotContain("web-researcher").doesNotContain("file-finder").doesNotContain("explorer");
    }
}
