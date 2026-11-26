package ai.mindconnect.agent.tools.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundProcessesKillTreeTest {

    @TempDir
    Path tmp;

    /**
     * A shell told to stop must not get to its next command. Killed after
     * its child, it saw the child die and went on — a timeout in
     * {@code build; deploy} could still start the deploy. Many rounds,
     * because the shell wins that race only now and then.
     */
    @Test
    void a_killed_shell_does_not_run_the_command_after_the_one_it_was_killed_in() throws Exception {
        for (int round = 0; round < 400; round++) {
            Path marker = tmp.resolve("ran-on-" + round);
            Process shell = new ProcessBuilder("bash", "-c", "sleep 30; echo went on > '" + marker + "'")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (shell.descendants().findAny().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }

            BackgroundProcesses.killTree(shell.toHandle());

            assertThat(shell.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.exists(marker)).as("round %d: the shell went on after its child was killed", round)
                    .isFalse();
        }
    }

    @Test
    void the_children_of_a_killed_shell_die_with_it_also_the_ones_of_a_shell_inside_it() throws Exception {
        Process shell = new ProcessBuilder("bash", "-c", "bash -c 'sleep 41.3; true'; true")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (shell.descendants().count() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        java.util.List<ProcessHandle> started = shell.descendants().toList();
        assertThat(started).as("the inner shell and its sleep").hasSize(2);

        BackgroundProcesses.killTree(shell.toHandle());

        assertThat(shell.waitFor(5, TimeUnit.SECONDS)).isTrue();
        for (ProcessHandle handle : started) {
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertThat(handle.isAlive()).isFalse();
        }
    }
}
