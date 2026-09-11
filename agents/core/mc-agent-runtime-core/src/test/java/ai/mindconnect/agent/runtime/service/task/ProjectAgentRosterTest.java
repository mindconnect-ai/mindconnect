package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.service.InlineAgentTools;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.service.agents.ProjectAgents;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's agent inherits the caller's roster along with its tools. The
 * roster is a permission: a file in the repository that is handed
 * {@code run_agent} must not reach an agent its caller could not.
 */
class ProjectAgentRosterTest {

    /** Inline agents resolve from the session alone; the registry is never asked. */
    private static final AgentDefinitionRepository NO_REGISTRY = new AgentDefinitionRepository() {
        @Override public AgentDefinition save(AgentDefinition d) { return d; }
        @Override public Optional<AgentDefinition> findById(AgentId id) { return Optional.empty(); }
        @Override public List<AgentDefinition> findAll() { return List.of(); }
        @Override public Optional<AgentDefinition> findByName(String name) { return Optional.empty(); }
        @Override public void deleteById(AgentId id) { }
    };

    /** A file without {@code tools:} — it inherits everything the caller has. */
    private static final ProjectAgents.ProjectAgent HELPER = new ProjectAgents.ProjectAgent(
            "helper", "", "Help with whatever comes up.", null, null, null);

    private static AgentDefinition caller(List<String> roster) {
        return AgentDefinition.create("main", "d", "p", null, "gpt")
                .withTools(List.of(AgentTool.of("file_read"), AgentTool.of(InlineAgentTools.RUN_AGENT)))
                .withCallableAgents(roster);
    }

    /** The definition the helper's sub-session runs — the path the turn and tool workers take. */
    private static AgentDefinition asRun(AgentDefinition caller) {
        var inline = SubAgentCalls.inlineFor(HELPER, caller);
        AgentSession session = AgentSession.start(inline.id(), UserId.of("alice"), ConversationId.random())
                .withSessionAgents(List.of(inline));
        return new SessionAgentResolver(NO_REGISTRY).resolve(session);
    }

    @Test
    void aProjectAgentReachesOnlyTheAgentsItsCallerMay() {
        AgentDefinition helper = asRun(caller(List.of("explorer")));

        assertThat(helper.tools()).extracting(AgentTool::name)
                .as("naming no tools, it inherits run_agent")
                .contains(InlineAgentTools.RUN_AGENT);
        assertThat(helper.mayCall("explorer")).isTrue();
        assertThat(helper.mayCall("deployer"))
                .as("an agent outside the caller's roster stays out of reach one level down")
                .isFalse();
        assertThat(helper.effectiveCallableAgents()).containsExactly("explorer");
    }

    @Test
    void aCallerWithoutARosterPassesNoneOn() {
        AgentDefinition helper = asRun(caller(null));

        assertThat(helper.effectiveCallableAgents()).isEmpty();
        assertThat(helper.mayCall("deployer")).as("no restriction, as for the caller").isTrue();
    }

    @Test
    void theRosterTravelsDownEveryLevel() {
        AgentDefinition first = asRun(caller(List.of("explorer")));
        AgentDefinition second = asRun(first);

        assertThat(second.mayCall("deployer")).isFalse();
        assertThat(second.effectiveCallableAgents()).containsExactly("explorer");
    }
}
