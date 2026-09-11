package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code list_agents} answers with the caller's roster, not with the whole
 * namespace — otherwise it would advertise agents that {@code run_agent} then
 * refuses, which is worse than not listing them at all.
 */
class ListAgentsToolTest {

    private static AgentDefinition agent(String name, List<String> roster) {
        return new AgentDefinition(AgentId.random(), name, name + " does things",
                null, null, "prompt", null, "cfg", 5, null, AgentDefinitionStatus.ACTIVE,
                List.of(), List.of(), roster, null, null, null);
    }

    /** In-memory repository over a fixed roster of definitions. */
    private static AgentDefinitionRepository repo(List<AgentDefinition> all) {
        return new AgentDefinitionRepository() {
            @Override public AgentDefinition save(AgentDefinition d) { return d; }
            @Override public Optional<AgentDefinition> findById(AgentId id) {
                return all.stream().filter(a -> a.id().equals(id)).findFirst();
            }
            @Override public Optional<AgentDefinition> findByName(String name) {
                return all.stream().filter(a -> a.name().equals(name)).findFirst();
            }
            @Override public List<AgentDefinition> findAll() { return all; }
            @Override public void deleteById(AgentId id) { }
        };
    }

    @Test
    void aCallerWithARosterSeesOnlyIt() {
        var planner = agent("planner", List.of("web-researcher", "verifier"));
        var all = List.of(planner, agent("web-researcher", null),
                agent("verifier", null), agent("title-generator", null));

        String out = new ListAgentsTool(repo(all), planner.id()).execute(Map.of());

        assertThat(out).contains("web-researcher").contains("verifier");
        assertThat(out).doesNotContain("title-generator");
        // Not even itself: the roster names who it delegates to, and an agent
        // does not delegate to itself.
        assertThat(out).doesNotContain("planner does things");
    }

    @Test
    void anEmptyRosterListsTheWholeNamespace() {
        var assistant = agent("general", List.of());
        var all = List.of(assistant, agent("web-researcher", null), agent("title-generator", null));

        String out = new ListAgentsTool(repo(all), assistant.id()).execute(Map.of());

        assertThat(out).contains("general").contains("web-researcher").contains("title-generator");
    }

    /**
     * A session's inline agent has an id with no definition behind it, and the
     * old two-argument constructor passes none at all. Both mean "no roster",
     * which is the whole namespace — not an empty answer.
     */
    @Test
    void anUnknownOrAbsentCallerIsNotARestriction() {
        var all = List.of(agent("web-researcher", null), agent("title-generator", null));

        assertThat(new ListAgentsTool(repo(all), AgentId.random()).execute(Map.of()))
                .contains("web-researcher").contains("title-generator");
        assertThat(new ListAgentsTool(repo(all)).execute(Map.of()))
                .contains("web-researcher").contains("title-generator");
    }

    @Test
    void anEmptyNamespaceSaysSoRatherThanReturningNothing() {
        assertThat(new ListAgentsTool(repo(List.of())).execute(Map.of()))
                .startsWith("No agents found");
    }

    @TempDir
    Path project;

    @Test
    void theProjectsOwnAgentsAreListedFirst_andShadowARegisteredNamesake() throws Exception {
        Path dir = Files.createDirectories(
                project.resolve(ai.mindconnect.agent.runtime.service.agents.ProjectAgents.DIR));
        Files.writeString(dir.resolve("verifier.md"), """
                ---
                description: Knows how this project is built
                ---
                Verify.
                """);
        var all = List.of(agent("verifier", null), agent("web-researcher", null));

        String out = new ListAgentsTool(repo(all), null, project.toString()).execute(Map.of());

        assertThat(out).startsWith("- verifier (this project): Knows how this project is built");
        assertThat(out).contains("- web-researcher");
        assertThat(out).as("the registered namesake is not offered twice")
                .doesNotContain("verifier does things");
    }

    @Test
    void withoutAWorkingDirectoryTheAnswerIsTheRegistryAlone() {
        var all = List.of(agent("web-researcher", null));

        assertThat(new ListAgentsTool(repo(all), null, null).execute(Map.of()))
                .isEqualTo("- web-researcher: web-researcher does things");
    }
}
