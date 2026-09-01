package ai.mindconnect.agent.domain;

import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roster rule, which both halves of delegating go through: {@code
 * list_agents} filters with it and a {@code run_agent} is refused by it. It
 * lives here, on the definition, so the two can never drift into disagreeing
 * about what an agent is allowed to reach.
 */
class AgentDefinitionRosterTest {

    private static AgentDefinition withRoster(List<String> roster) {
        return new AgentDefinition(UUID.randomUUID(), new Namespace("local"), "planner", "d",
                null, null, "prompt", null, "cfg", 5, null, AgentDefinitionStatus.ACTIVE,
                List.of(), List.of(), roster, null, null, null);
    }

    @Test
    void namingNobodyReachesEveryone() {
        // The default for every agent that predates the field, and for one
        // whose roster was cleared: no entries is no restriction, not a ban.
        assertThat(withRoster(null).mayCall("verifier")).isTrue();
        assertThat(withRoster(List.of()).mayCall("verifier")).isTrue();
        assertThat(withRoster(null).effectiveCallableAgents()).isEmpty();
    }

    @Test
    void aRosterAdmitsWhatItNamesAndNothingElse() {
        var planner = withRoster(List.of("web-researcher", "verifier"));

        assertThat(planner.mayCall("web-researcher")).isTrue();
        assertThat(planner.mayCall("verifier")).isTrue();
        assertThat(planner.mayCall("title-generator")).isFalse();
        assertThat(planner.mayCall(null)).isFalse();
    }

    /**
     * A sub-agent call resolves its target with {@code equalsIgnoreCase}, so
     * the check has to as well — otherwise "Web-Researcher" would find the
     * agent and then be turned away by its own roster.
     */
    @Test
    void caseIsIgnoredHereBecauseTheNameLookupIgnoresIt() {
        var planner = withRoster(List.of("Web-Researcher"));

        assertThat(planner.mayCall("web-researcher")).isTrue();
        assertThat(planner.mayCall("WEB-RESEARCHER")).isTrue();
    }

    @Test
    void theRosterIsReplacedWholeAndClearingItRestoresTheDefault() {
        var planner = withRoster(List.of("verifier"));

        assertThat(planner.withCallableAgents(List.of("explorer")).mayCall("verifier")).isFalse();
        assertThat(planner.withCallableAgents(List.of("explorer")).mayCall("explorer")).isTrue();
        assertThat(planner.withCallableAgents(List.of()).mayCall("anything")).isTrue();
    }
}
