package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roster rule, which both halves of delegating go through, and the tools
 * derived from the definition: {@code
 * list_agents} filters with it and a {@code run_agent} is refused by it. It
 * lives here, on the definition, so the two can never drift into disagreeing
 * about what an agent is allowed to reach.
 */
class AgentDefinitionRosterTest {

    private static AgentDefinition withRoster(List<String> roster) {
        return new AgentDefinition(AgentId.random(), "planner", "d",
                null, null, "prompt", null, "cfg", 5, null, AgentDefinitionStatus.ACTIVE,
                List.of(), List.of(), roster, null, null, null);
    }

    @Test
    void namingNobodyReachesNobody() {
        // Delegating is given agent by agent, never a default.
        assertThat(withRoster(null).mayCall("verifier")).isFalse();
        assertThat(withRoster(List.of()).mayCall("verifier")).isFalse();
        assertThat(withRoster(null).effectiveCallableAgents()).isEmpty();
        assertThat(withRoster(null).delegates()).isFalse();
        assertThat(withRoster(List.of("verifier")).delegates()).isTrue();
    }

    /**
     * The delegation tools and tool_search are the runtime's to add — a list
     * that names them loses them on the way in, whatever it was written by.
     */
    @Test
    void derivedToolsNeverStayInTheToolList() {
        var def = withRoster(null).withTools(List.of(
                AgentTool.of("file_read"), AgentTool.of("run_agent"), AgentTool.of("run_agents"),
                AgentTool.of("list_agents"), AgentTool.of("tool_search")));

        assertThat(def.tools()).extracting(AgentTool::name).containsExactly("file_read");
    }

    @Test
    void toolSearchFollowsTheDeferredTools() {
        var plain = withRoster(null).withTools(List.of(AgentTool.of("file_read")));
        var deferred = new AgentTool(ai.mindconnect.agent.tool.AgentToolId.random(), "gmail_read_email",
                null, java.util.Map.of(), true, true, false, null);

        assertThat(plain.searchesTools()).isFalse();
        assertThat(plain.withTools(List.of(AgentTool.of("file_read"), deferred)).deferredToolNames())
                .containsExactly("gmail_read_email");
    }

    /** The flag survives JSON as stored: {@code false} stays false, absent stays absent. */
    @Test
    void theCallableFlagSurvivesJson() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var helper = withRoster(null).withCallableByAgents(false);

        var read = json.readValue(json.writeValueAsString(helper), AgentDefinition.class);
        assertThat(read.mayBeCalledByAgents()).isFalse();
        assertThat(json.readValue(json.writeValueAsString(withRoster(null)), AgentDefinition.class)
                .callableByAgents()).isNull();
    }

    @Test
    void anAgentIsCallableByOthersUnlessItSaysNot() {
        assertThat(withRoster(null).mayBeCalledByAgents()).as("null reads as yes").isTrue();
        assertThat(withRoster(null).withCallableByAgents(false).mayBeCalledByAgents()).isFalse();
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
    void theRosterIsReplacedWholeAndClearingItReachesNobody() {
        var planner = withRoster(List.of("verifier"));

        assertThat(planner.withCallableAgents(List.of("explorer")).mayCall("verifier")).isFalse();
        assertThat(planner.withCallableAgents(List.of("explorer")).mayCall("explorer")).isTrue();
        assertThat(planner.withCallableAgents(List.of()).mayCall("anything")).isFalse();
    }
}
