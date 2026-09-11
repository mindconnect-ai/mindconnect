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
    void aLineLongerThanTheCapKeepsItsHead_andTheRestIsCounted() {
        BashTool bash = new BashTool(tmp.toFile());
        int max = BashTool.MAX_OUTPUT_CHARS;

        // What curl returning 40 KB of one-line JSON looks like: the head, not just the note.
        String out = bash.execute(Map.of("command", "printf '%*s' 40000 '' | tr ' ' x"));
        assertThat(out).isEqualTo("x".repeat(max) + "\n\n[output cut after " + max
                + " chars — 1 more line(s), " + (40_000 - max) + " chars, not shown; pipe through head, tail or grep]");

        // A line that never ends is not held in memory: it is cut at the cap while it grows.
        String endless = bash.execute(Map.of("command", "yes | tr -d '\\n'", "timeout", 1));
        assertThat(endless).startsWith("Error: command timed out after 1 seconds\nOutput so far:\nyyyy")
                .contains("[output cut after " + max + " chars — 1 more line(s), ");
        assertThat(endless.length()).isLessThan(max + 400);
    }

    @Test
    void theOutputCountsLinesAsTheyWereShown() {
        BashTool.Output output = new BashTool.Output();
        char[] crlf = "one\r\ntwo\rthree\n".toCharArray();
        output.add(crlf, 0, crlf.length);
        assertThat(output.text()).as("line breaks normalised, the last one dropped").isEqualTo("one\ntwo\nthree");

        BashTool.Output capped = new BashTool.Output();
        char[] exact = ("a".repeat(BashTool.MAX_OUTPUT_CHARS) + "\nb\nc\n").toCharArray();
        capped.add(exact, 0, exact.length);
        assertThat(capped.text()).as("a line kept whole is not counted, though its break fell past the cap")
                .isEqualTo("a".repeat(BashTool.MAX_OUTPUT_CHARS) + "\n\n[output cut after " + BashTool.MAX_OUTPUT_CHARS
                        + " chars — 2 more line(s), 4 chars, not shown; pipe through head, tail or grep]");
    }

    @Test
    void aChildHoldingTheOutputOpenDoesNotHoldTheCall() {
        BashTool bash = new BashTool(tmp.toFile());
        String marker = "sleep 31.7";
        try {
            // The quoted & passes the detach check; the inner shell exits at
            // once and its sleep keeps the pipe — which used to hold the call
            // for as long as the sleep ran.
            long start = System.currentTimeMillis();
            String out = bash.execute(Map.of("command", "echo before; bash -c '" + marker + " &'; echo after"));
            long took = System.currentTimeMillis() - start;

            assertThat(out).startsWith("before\nafter\n")
                    .contains("kept the output open after the shell exited").contains("background=true");
            assertThat(took).as("returns shortly after the shell").isLessThan(BashTool.OUTPUT_GRACE_MS + 4_000);
        } finally {
            killAll(marker);
        }
    }

    @Test
    void aChildHoldingTheOutputOpenIsKilled_whenItWasSeenWhileTheShellRan() throws Exception {
        BashTool bash = new BashTool(tmp.toFile());
        String marker = "sleep 32.7";
        try {
            long start = System.currentTimeMillis();
            String out = bash.execute(Map.of("command", "bash -c '" + marker + " & sleep 1.5; echo shell done'"));
            long took = System.currentTimeMillis() - start;

            assertThat(out).startsWith("shell done\n").contains("it was killed.");
            assertThat(took).isLessThan(1_500 + BashTool.OUTPUT_GRACE_MS + 4_000);
            Thread.sleep(300);
            assertThat(alive(marker)).as("the sleep that held the output is gone").isZero();
        } finally {
            killAll(marker);
        }
    }

    @Test
    void aCancelledCallReturnsAtOnce_evenWhileWaitingForHeldOutput() throws Exception {
        BashTool bash = new BashTool(tmp.toFile());
        String marker = "sleep 33.7";
        try {
            java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
            Thread caller = new Thread(() -> result.set(bash.execute(Map.of("command", "bash -c '" + marker + " &'"))));
            long start = System.currentTimeMillis();
            caller.start();
            Thread.sleep(300);
            caller.interrupt();
            caller.join(5_000);
            long took = System.currentTimeMillis() - start;

            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isEqualTo("Error: bash command cancelled");
            assertThat(took).as("not after the grace period").isLessThan(BashTool.OUTPUT_GRACE_MS);
        } finally {
            killAll(marker);
        }
    }

    @Test
    void heredocBodiesAndCommentsAreText_notShellCode() throws Exception {
        BashTool bash = new BashTool(tmp.toFile());

        String page = "cat > index.html <<'EOF'\n<p>Tom & Jerry</p>\n<p>don't</p>\nEOF\necho written";
        assertThat(bash.execute(Map.of("command", page))).isEqualTo("written");
        assertThat(Files.readString(tmp.resolve("index.html"))).isEqualTo("<p>Tom & Jerry</p>\n<p>don't</p>\n");
        assertThat(bash.execute(Map.of("command", "# build & test\necho ok # it's done & dusted"))).isEqualTo("ok");
        assertThat(BashTool.detaches("cat <<-\"END\" | sort\n\tb & a\n\tEND\n")).isNull();
        assertThat(BashTool.detaches("cat <<A <<\\B\n&\nA\n&\nB")).isNull();
        assertThat(BashTool.detaches("x=$(cat <<EOF\nfish & chips\nEOF\n); echo \"$x\"")).isNull();

        // An apostrophe in a body used to open a quote that hid what came after it.
        assertThat(BashTool.detaches("cat > notes.txt <<EOF\ndon't\nEOF\nsleep 600 &")).isEqualTo("`&`");
        assertThat(BashTool.detaches("cat <<'EOF'\n&\nEOF\nnohup sleep 600")).isEqualTo("`nohup`");
        assertThat(BashTool.detaches("cat <<-EOF\n\tit's\n\tEOF\nsleep 600 &")).isEqualTo("`&`");
        assertThat(BashTool.detaches("echo # it's\nsleep 600 &")).isEqualTo("`&`");
        assertThat(BashTool.detaches("echo a#b &")).as("# inside a word is no comment").isEqualTo("`&`");
        assertThat(BashTool.detaches("cat <<< word &")).as("a here-string has no body").isEqualTo("`&`");
        assertThat(BashTool.detaches("echo $((1 << 2))\nsleep 600 &")).as("a shift is no here-document")
                .isEqualTo("`&`");
    }

    private static long alive(String marker) {
        return ProcessHandle.allProcesses().filter(h -> h.info().commandLine().orElse("").endsWith(marker)).count();
    }

    private static void killAll(String marker) {
        ProcessHandle.allProcesses().filter(h -> h.info().commandLine().orElse("").endsWith(marker))
                .forEach(ProcessHandle::destroyForcibly);
    }

    @Test
    void aCommandThatLetsAProcessGoIsRefused_beforeAnythingStarts() {
        BashTool bash = new BashTool(tmp.toFile());

        // What a model did in a manual run: the server outlived the call, and
        // process_kill never heard of it.
        assertThat(bash.execute(Map.of("command", "sleep 41.7 > /dev/null 2>&1 & echo $!")))
                .startsWith("Error: `&` would leave the process running on its own")
                .contains("background=true");
        assertThat(bash.execute(Map.of("command", "sleep 41.7 &", "background", true)))
                .as("background tracks the shell, which is gone as soon as it has let go")
                .startsWith("Error: `&`");
        assertThat(bash.execute(Map.of("command", "nohup sleep 41.7")))
                .startsWith("Error: `nohup`");
        assertThat(bash.execute(Map.of("command", "true; setsid sleep 41.7")))
                .startsWith("Error: `setsid`");
        assertThat(ProcessHandle.allProcesses()
                .map(p -> p.info().commandLine().orElse(""))
                .filter(line -> line.contains("sleep 41.7")))
                .as("refused before it started").isEmpty();
    }

    @Test
    void ampersandsThatDetachNothingStillRun() {
        BashTool bash = new BashTool(tmp.toFile());

        assertThat(bash.execute(Map.of("command", "true && echo and"))).isEqualTo("and");
        assertThat(bash.execute(Map.of("command", "echo 'fish & chips'"))).isEqualTo("fish & chips");
        assertThat(bash.execute(Map.of("command", "echo \"quoted &\" 2>&1"))).isEqualTo("quoted &");
        assertThat(bash.execute(Map.of("command", "ls /does-not-exist &> /dev/null; echo redirected")))
                .isEqualTo("redirected");
        assertThat(bash.execute(Map.of("command", "echo nohup is only an argument here")))
                .isEqualTo("nohup is only an argument here");
        assertThat(bash.execute(Map.of("command", "(sleep 0.2; echo one) & (sleep 0.1; echo two) & wait")))
                .as("jobs the command waits for end with it").contains("one").contains("two");
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
