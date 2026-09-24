package ai.mindconnect.agent.tools.virtualenv;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session directory on this machine means {@code /workspace} in the container,
 * in the command and in what goes to it on stdin — {@code code_execute} sends the
 * whole program that way.
 */
class RemoteCommandRunnerTest {

    private static final String LOCAL = "/data/sessions/abc/work";

    /** Records what would go to the server; the environment is already running. */
    private static class RecordingClient extends VirtualEnvClient {
        String command;
        String stdin;

        RecordingClient() {
            super(URI.create("http://virtual-env.invalid"), TokenSource.none(), Duration.ofSeconds(1));
        }

        @Override
        public ExecResult exec(WorkspaceKey key, String id, String command, String stdin, Map<String, String> env,
                               long timeoutSeconds) {
            this.command = command;
            this.stdin = stdin;
            return new ExecResult(0, "", false, false, 1);
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final WorkspaceKey key = new WorkspaceKey("python", "session-1", null);
    private final RemoteCommandRunner runner = new RemoteCommandRunner(client, key,
            new ConcurrentHashMap<>(Map.of(key.id(), "env-1")), Duration.ofMinutes(1), LOCAL);

    @Test
    void the_local_session_directory_becomes_workspace_in_the_program_on_stdin() throws Exception {
        runner.run("python3 -", "open('" + LOCAL + "/data.csv').read()\nprint('" + LOCAL + "')\n",
                Map.of(), Duration.ofSeconds(10));

        assertThat(client.command).isEqualTo("python3 -");
        assertThat(client.stdin).isEqualTo("open('/workspace/data.csv').read()\nprint('/workspace')\n");
    }

    @Test
    void the_command_is_mapped_and_a_missing_stdin_stays_missing() throws Exception {
        runner.run("ls " + LOCAL + "/out", null, Map.of(), Duration.ofSeconds(10));

        assertThat(client.command).isEqualTo("ls /workspace/out");
        assertThat(client.stdin).isNull();
    }
}
