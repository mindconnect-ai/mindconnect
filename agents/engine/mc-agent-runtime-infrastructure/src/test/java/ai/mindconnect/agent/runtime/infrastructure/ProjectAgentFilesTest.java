package ai.mindconnect.agent.runtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A project's agent files may narrow what the calling agent is allowed, and nothing else. */
class ProjectAgentFilesTest {

    private static final ToolBinding BASH = new ToolBinding("bash", false, Map.of("needsApproval", true));
    private static final AgentDefinition CALLER = new AgentDefinition("coder", "Codes.", "You code.",
            ModelSettings.ofModel("big"),
            List.of(BASH, ToolBinding.of("file_read"), ToolBinding.deferred("read_pdf")), 12,
            Map.of(ManagedAgent.AGENT_ID, "a1", SubAgentPlugin.SUB_AGENTS, List.of("explorer"),
                    "responseReviewers", List.of("checker")));

    @TempDir
    Path project;

    private final ProjectAgentFiles files = new ProjectAgentFiles();
    private Session session;

    @BeforeEach
    void a_session_working_in_the_project() throws IOException {
        Files.createDirectories(project.resolve(".mindconnect/agents"));
        session = new Session(SessionId.of("s1"), UserId.of("ada"), null, CALLER,
                Map.of(ManagedSession.WORKING_DIRECTORY, project.toString()), Instant.EPOCH);
    }

    @Test
    void a_file_with_only_a_body_is_an_agent_named_after_the_file_with_the_callers_tools_model_and_roster()
            throws IOException {
        write("reader.md", "You read.");

        AgentDefinition reader = files.agents(CALLER, session).get(0);

        assertThat(reader.name()).isEqualTo("reader");
        assertThat(reader.instructions()).isEqualTo("You read.");
        assertThat(reader.tools()).isEqualTo(CALLER.tools());
        assertThat(reader.modelSettings()).isEqualTo(CALLER.modelSettings());
        assertThat(reader.maxRounds()).isEqualTo(12);
        assertThat(reader.metadata()).isEqualTo(
                Map.of(ManagedAgent.AGENT_ID, "a1", SubAgentPlugin.SUB_AGENTS, List.of("explorer")));
    }

    @Test
    void tools_are_narrowed_and_never_granted_and_keep_the_callers_binding() throws IOException {
        write("verifier.md", """
                ---
                name: checker
                description: Checks the build
                model: small
                tools:
                  - bash
                  - read_pdf
                  - web_search
                ---
                You verify.""");
        write("quiet.md", "---\ntools: []\n---\nYou think.");
        write("careful.md", "---\ndisallowedTools: bash\n---\nYou read.");

        List<AgentDefinition> agents = files.agents(CALLER, session);

        AgentDefinition checker = agents.stream().filter(a -> a.name().equals("checker")).findFirst().orElseThrow();
        assertThat(checker.description()).isEqualTo("Checks the build");
        assertThat(checker.modelSettings().model()).isEqualTo("small");
        // bash with the caller's approval; the deferred tool named by hand is offered outright; no web_search.
        assertThat(checker.tools()).containsExactly(BASH, ToolBinding.of("read_pdf"));
        assertThat(agents.stream().filter(a -> a.name().equals("quiet")).findFirst().orElseThrow().tools()).isEmpty();
        assertThat(agents.stream().filter(a -> a.name().equals("careful")).findFirst().orElseThrow().tools())
                .containsExactly(ToolBinding.of("file_read"), ToolBinding.deferred("read_pdf"));
    }

    @Test
    void a_project_without_agents_and_a_session_without_a_directory_bring_none() throws IOException {
        write("empty.md", "---\nname: nothing\n---\n");
        write("notes.txt", "not an agent");

        assertThat(files.agents(CALLER, session)).isEmpty();
        assertThat(files.agents(CALLER, new Session(SessionId.of("s2"), UserId.of("ada"), null, CALLER, Map.of(),
                Instant.EPOCH))).isEmpty();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(project.resolve(".mindconnect/agents").resolve(name), content);
    }
}
