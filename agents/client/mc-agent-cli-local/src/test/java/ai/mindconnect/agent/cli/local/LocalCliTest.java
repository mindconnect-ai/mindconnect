package ai.mindconnect.agent.cli.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The harness with a scripted model and a scripted user, on real files in a temporary home and project. */
class LocalCliTest {

    @TempDir
    Path home;
    @TempDir
    Path project;

    private final Deque<TurnMessage> script = new ArrayDeque<>();
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private AgentRuntime runtime;

    @BeforeEach
    void start() {
        runtime = new LocalRuntime(home).builder()
                .model(new ModelGateway() {
                    @Override
                    public synchronized LlmAnswer ask(ModelRequest request, TurnEventListener events) {
                        requests.add(request);
                        return LlmAnswer.of(List.of(script.pop()), new Usage(10, 1));
                    }

                    @Override
                    public void cancel(TurnId turnId) {
                    }
                })
                .without(SessionTitlePlugin.class)
                .build();
    }

    @AfterEach
    void stop() {
        runtime.close();
    }

    @Test
    void the_built_in_agent_reads_a_file_of_the_project_without_asking() throws IOException {
        Files.writeString(project.resolve("README.md"), "A project about owls.");
        script.add(call("c1", "read_file", Map.of("path", "README.md")));
        script.add(TurnMessage.assistant("It is about owls."));

        converse(LocalRuntime.DEFAULT_AGENT, "What is this?\n");

        assertThat(output()).contains("It is about owls.").doesNotContain("Allow");
        assertThat(requests.get(1).messages()).extracting(TurnMessage::text).contains("A project about owls.");
    }

    @Test
    void writing_a_file_asks_the_user_and_always_is_kept_in_the_projects_settings() throws IOException {
        script.add(call("c1", "write_file", Map.of("path", "notes.txt", "content", "hello")));
        script.add(TurnMessage.assistant("Written."));

        converse(LocalRuntime.DEFAULT_AGENT, "Take a note\na\n");

        assertThat(output()).contains("Allow write_file", "Written.");
        assertThat(project.resolve("notes.txt")).hasContent("hello");
        assertThat(project.resolve(".mc/settings.json")).content().contains("write_file");
    }

    @Test
    void an_agent_and_a_skill_from_the_projects_mc_folder_are_used() throws IOException {
        Files.createDirectories(project.resolve(".mc/agents"));
        Files.writeString(project.resolve(".mc/agents/reviewer.md"), """
                ---
                description: Reviews code
                tools: read_file
                ---
                You review code.
                """.stripIndent().stripLeading());
        Files.createDirectories(project.resolve(".mc/skills/style"));
        Files.writeString(project.resolve(".mc/skills/style/SKILL.md"), """
                ---
                description: The house style
                ---
                Use tabs.
                """.stripIndent().stripLeading());
        script.add(TurnMessage.assistant("Looks fine."));

        converse("reviewer", "Review it\n");

        ModelRequest asked = requests.get(0);
        assertThat(asked.instructions()).startsWith("You review code.").contains("style: The house style");
        assertThat(asked.tools()).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("read_file", "skill");
        assertThat(output()).contains("Looks fine.");
    }

    private void converse(String agent, String typed) {
        Session session = runtime.sessions().open(new LocalRuntime(home).sessionIn(project, UserId.of("ada"), agent));
        new LocalCli(runtime, session, new BufferedReader(new StringReader(typed)),
                new PrintStream(printed, true, StandardCharsets.UTF_8)).converse();
    }

    private String output() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    private static TurnMessage call(String callId, String toolName, Map<String, Object> arguments) {
        return TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall(callId, toolName, arguments)));
    }
}
