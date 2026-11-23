package ai.mindconnect.agent.management.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryPromptPluginTest {

    private static final AgentDefinition AGENT = new AgentDefinition("coder", "Codes.", "You code.",
            ModelSettings.NONE, List.of(), 5, Map.of());

    @TempDir
    Path home;
    @TempDir
    Path project;

    @Test
    void the_instructions_name_the_directory_then_the_users_then_the_projects_file() throws IOException {
        Files.writeString(home.resolve("AGENTS.md"), "Answer in German.");
        Files.writeString(project.resolve("AGENTS.md"), "Build with Maven.");

        String instructions = instructions(Map.of(ManagedSession.WORKING_DIRECTORY, project.toString(),
                ManagedSession.ADDITIONAL_DIRECTORIES, List.of("/data/shared")));

        assertThat(instructions).startsWith("You code.\n\n## Working directory\nYou are working in `" + project + "`.")
                .contains("by absolute path:\n- `/data/shared`");
        assertThat(instructions.indexOf("## Working directory"))
                .isLessThan(instructions.indexOf("## User instructions"));
        assertThat(instructions.indexOf("Answer in German."))
                .isLessThan(instructions.indexOf("## Project instructions"));
        assertThat(instructions).endsWith("Build with Maven.");
    }

    @Test
    void a_session_that_works_nowhere_and_a_user_without_a_file_add_nothing() {
        assertThat(instructions(Map.of())).isEqualTo("You code.");
    }

    private String instructions(Map<String, Object> metadata) {
        PluginRegistry registry = new DefaultPluginRegistry()
                .install(new DirectoryPromptPlugin(InstructionFileReader.of(home.toString())));
        Session session = new Session(SessionId.of("s1"), UserId.of("ada"), null, null, metadata, Instant.EPOCH);
        return registry.run(RuntimeHook.INSTRUCTIONS, new AgentContext(session, AGENT, TurnId.of("t1")),
                ignored -> "You code.");
    }
}
