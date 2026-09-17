package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.workspace.CommandRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteBashToolTest {

    private final List<String> commands = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private CommandRunner.Result next = new CommandRunner.Result(0, "ok\n", false, false, 5);

    private final CommandRunner runner = new CommandRunner() {
        @Override
        public String workingDirectory() {
            return "/workspace";
        }

        @Override
        public Result run(String command, String stdin, Map<String, String> env, Duration timeout) {
            commands.add(command);
            timeouts.add(timeout);
            return next;
        }
    };

    @Test
    void runs_the_command_as_given_with_the_bash_tool_result_shape() {
        RemoteBashTool bash = new RemoteBashTool(runner);

        assertThat(bash.execute(Map.of("command", "python3 make_deck.py && ls", "timeout", 300))).isEqualTo("ok");
        assertThat(commands).containsExactly("python3 make_deck.py && ls");
        assertThat(timeouts).containsExactly(Duration.ofSeconds(300));

        next = new CommandRunner.Result(2, "No such file\n", false, false, 5);
        assertThat(bash.execute(Map.of("command", "cat nope"))).isEqualTo("Exit code 2:\nNo such file");

        next = new CommandRunner.Result(137, "start\n", false, true, 120_000);
        assertThat(bash.execute(Map.of("command", "sleep 999"))).isEqualTo("Error: command timed out after 120 seconds\nstart");
    }

    @Test
    void a_background_command_starts_in_its_own_session_and_is_recorded() {
        new RemoteBashTool(runner).execute(Map.of("command", "npm run dev -- --port '3000'", "background", true));

        assertThat(commands.getFirst())
                .contains("setsid nohup bash -c 'npm run dev -- --port '\\''3000'\\'''")
                .contains(">> .mc/processes");
    }

    @Test
    void process_list_and_a_kill_without_pid_list_what_is_still_running_in_the_container() {
        next = new CommandRunner.Result(0, "pid 42 (running): npm run dev — log .mc/logs/42.log\n", false, false, 5);

        assertThat(new RemoteProcessListTool(runner).execute(Map.of()))
                .isEqualTo("pid 42 (running): npm run dev — log .mc/logs/42.log");
        assertThat(new RemoteProcessKillTool(runner).execute(Map.of())).startsWith("pid 42 (running)");
        assertThat(commands).allSatisfy(c -> assertThat(c).contains("kill -0").contains(".mc/processes"));
        assertThat(new RemoteProcessListTool(runner).description()).isEqualTo(new ProcessListTool(null).description());
    }

    @Test
    void has_the_same_parameters_as_the_local_bash_tool() {
        assertThat(new RemoteBashTool(runner).parametersSchema().get("properties"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("command", "timeout", "background");
        assertThat(new BashTool(java.nio.file.Path.of(".").toFile()).parametersSchema().get("properties"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("command", "timeout", "background");
    }
}
