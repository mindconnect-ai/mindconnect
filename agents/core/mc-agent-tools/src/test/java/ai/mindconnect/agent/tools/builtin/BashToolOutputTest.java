package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bash: output is drained while the command runs — so a command that prints
 * more than a pipe holds finishes instead of timing out — capped at the
 * output limit, and the caller's timeout is honoured.
 */
class BashToolOutputTest {

    @TempDir
    Path tmp;

    @Test
    void aCommandPrintingMoreThanThePipeHoldsFinishes_andTheOutputIsCapped() {
        BashTool bash = new BashTool(tmp.toFile());

        // 200,000 lines of ~40 chars: far beyond the 64 KB pipe buffer, and beyond the output cap.
        long start = System.currentTimeMillis();
        String out = bash.execute(Map.of("command", "seq 1 200000 | sed 's/^/line number is now /'"));
        long took = System.currentTimeMillis() - start;

        assertThat(out).doesNotStartWith("Error").startsWith("line number is now 1\n")
                .contains("[output cut after " + BashTool.MAX_OUTPUT_CHARS + " chars — ")
                .contains("more line(s)");
        assertThat(out.length()).isLessThan(BashTool.MAX_OUTPUT_CHARS + 300);
        assertThat(took).as("no false timeout").isLessThan(30_000);
    }

    @Test
    void theTimeoutIsPerCall_andWhatWasPrintedComesBackWithTheError() {
        BashTool bash = new BashTool(tmp.toFile());

        String out = bash.execute(Map.of("command", "echo started; sleep 30; echo never", "timeout", 1));

        assertThat(out).startsWith("Error: command timed out after 1 seconds")
                .contains("Output so far:\nstarted").doesNotContain("never");
        assertThat(BashTool.timeoutSeconds(null)).isEqualTo(BashTool.DEFAULT_TIMEOUT_SECONDS);
        assertThat(BashTool.timeoutSeconds(5000)).isEqualTo(BashTool.MAX_TIMEOUT_SECONDS);
        assertThat(BashTool.timeoutSeconds("abc")).isEqualTo(BashTool.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void runsInTheWorkingDirectory_andReportsExitCodes() throws Exception {
        Files.writeString(tmp.resolve("marker.txt"), "here");
        BashTool bash = new BashTool(tmp.toFile());

        assertThat(bash.execute(Map.of("command", "cat marker.txt"))).isEqualTo("here");
        assertThat(bash.execute(Map.of("command", "pwd"))).isEqualTo(tmp.toRealPath().toString());
        assertThat(bash.execute(Map.of("command", "echo oops >&2; exit 3"))).isEqualTo("Exit code 3:\noops");
        assertThat(bash.execute(Map.of("command", "true"))).isEqualTo("(no output)");
    }

    @Test
    void aTimeoutKillsTheWholeProcessTree() throws Exception {
        BashTool bash = new BashTool(tmp.toFile());
        String marker = "sleep 61.7";

        String out = bash.execute(Map.of("command", "sh -c '" + marker + "' & echo child started; wait", "timeout", 1));

        assertThat(out).startsWith("Error: command timed out").contains("background=true");
        Thread.sleep(300);
        long stillAlive = ProcessHandle.allProcesses()
                .filter(h -> h.info().commandLine().orElse("").endsWith(marker)).count();
        assertThat(stillAlive).as("the grandchild sleep is gone with the shell").isZero();
    }

    @Test
    void aBackgroundCommandReturnsAtOnce_logsToTheSessionDirectory_andIsKilledByPid() throws Exception {
        SessionId session = SessionId.random();
        Path own = Files.createDirectories(tmp.resolve("home/u/sessions/" + session));
        BashTool bash = new BashTool(ai.mindconnect.agent.tool.FileRoots.of(own), session);
        ProcessKillTool kill = new ProcessKillTool(session);

        String out = bash.execute(Map.of("command", "echo up; sleep 60", "background", true));
        assertThat(out).startsWith("Started in background: pid ").contains("Log: " + own.resolve("logs"))
                .contains("Status: still running after").contains("--- log so far ---\nup\n--- end of log ---");
        long pid = Long.parseLong(out.substring("Started in background: pid ".length(), out.indexOf('\n')));
        Path logFile = Path.of(out.substring(out.indexOf("Log: ") + 5, out.indexOf('\n', out.indexOf("Log: "))));
        assertThat(Files.readString(logFile)).isEqualTo("$ echo up; sleep 60\nup\n");

        assertThat(kill.execute(Map.of())).contains("pid " + pid + " (running): echo up; sleep 60");
        assertThat(kill.execute(Map.of("pid", pid))).startsWith("Killed pid " + pid);
        Thread.sleep(300);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(kill.execute(Map.of("pid", pid))).startsWith("Error: pid " + pid + " is not a background process");
        assertThat(kill.execute(Map.of())).isEqualTo("No background processes in this session.");
    }

    @Test
    void aServerCommandInTheForegroundIsRefused_unlessATimeoutSaysOtherwise() {
        BashTool bash = new BashTool(tmp.toFile());

        for (String server : java.util.List.of("npm run dev", "cd app && npm start", "mvn -q spring-boot:run",
                "docker compose up", "./gradlew bootRun", "tail -f app.log", "python -m http.server 8000")) {
            assertThat(BashTool.looksLongRunning(server)).as(server).isTrue();
        }
        for (String plain : java.util.List.of("npm test", "npm run build", "mvn -q install", "docker compose up -d",
                "ls -la", "git status", "npm run devtools:lint")) {
            assertThat(BashTool.looksLongRunning(plain)).as(plain).isFalse();
        }
        assertThat(bash.execute(Map.of("command", "npm run dev")))
                .startsWith("Error: this looks like a server or watcher").contains("background=true");
        assertThat(bash.execute(Map.of("command", "echo dev; tail -f /dev/null", "timeout", 1)))
                .as("an explicit timeout runs it in the foreground after all").startsWith("Error: command timed out");
    }

    @Test
    void aBackgroundCommandThatDiesAtOnceSaysSo_withItsErrors() {
        BashTool bash = new BashTool(ai.mindconnect.agent.tool.FileRoots.of(tmp), SessionId.random());

        String out = bash.execute(Map.of("command", "echo building; echo 'src/app.ts(23,4): error TS1109' >&2; exit 2",
                "background", true));

        assertThat(out).contains("Status: EXITED after").contains("with code 2 — it is not running.")
                .contains("error TS1109").endsWith("Read the errors above and fix them before starting again.");
    }
}
