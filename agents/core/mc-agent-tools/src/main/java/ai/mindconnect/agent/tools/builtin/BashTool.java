package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell command in the session's working directory and returns
 * what it printed. Output is drained while the command runs — a command
 * that prints more than the pipe holds used to block on a full pipe until
 * it was declared timed out — capped at {@link #MAX_OUTPUT_CHARS}, the
 * head kept and the tail summarised. The caller may set a timeout up to
 * {@link #MAX_TIMEOUT_SECONDS}; a command still running at the deadline is
 * killed — the whole process tree, not just the shell — and what it
 * printed so far comes back with the error.
 *
 * <p>A command that is not meant to return — a dev server, a watcher —
 * runs with {@code background}: it is started, its output goes to a log
 * file, and the call returns at once with the pid and the log's path. The
 * model reads the log like any file and ends the process with
 * {@code process_kill}; whatever is still running when the runtime stops
 * is killed with it.
 */
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_TIMEOUT_SECONDS = 600;
    /** The most output one call returns; the rest is counted, not shown. */
    static final int MAX_OUTPUT_CHARS = 30_000;
    /** How long a background start watches the process before answering — long enough for a build step to fail. */
    static final long BACKGROUND_WATCH_MS = 3_000;
    /** How much of the log a background start quotes. */
    static final int BACKGROUND_LOG_LINES = 40;

    private final File workingDir;
    /** The session's directories, when the tool is scoped to one; names the extra ones in the description. */
    private final FileRoots roots;
    /** The session a background process belongs to; {@code null} outside a session. */
    private final SessionId sessionId;

    public BashTool(File workingDir) {
        this.workingDir = workingDir;
        this.roots = null;
        this.sessionId = null;
    }

    public BashTool(FileRoots roots) {
        this(roots, null);
    }

    /** Rooted at the session's directories, background processes filed under the session. */
    public BashTool(FileRoots roots, SessionId sessionId) {
        this.workingDir = roots.base().toFile();
        this.roots = roots;
        this.sessionId = sessionId;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        String extra = roots == null || roots.extra().isEmpty() ? ""
                : " The session may also use these directories: " + String.join(", ",
                        roots.extra().stream().map(java.nio.file.Path::toString).toList()) + ".";
        return "Runs a bash command in the working directory " + workingDir.getAbsolutePath()
                + " and returns its combined stdout and stderr (at most " + MAX_OUTPUT_CHARS
                + " characters — pipe long output through head, tail or grep). Each call is a fresh "
                + "shell: cd and variables do not carry over. Default timeout " + DEFAULT_TIMEOUT_SECONDS
                + "s, raise `timeout` (up to " + MAX_TIMEOUT_SECONDS + ") for a build or test run. A command "
                + "that does not return on its own — a dev server, a watcher — must run with "
                + "`background`: the call returns at once with the pid and a log file to read; end it "
                + "with process_kill." + extra;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", Map.of("type", "string", "description", "The bash command to execute. A server "
                + "or watcher that keeps running (npm run dev, mvn spring-boot:run, docker compose up, tail -f) "
                + "needs background=true — in the foreground it is refused."));
        props.put("timeout", Map.of("type", "integer",
                "description", "Seconds the command may run before it is killed. Default "
                        + DEFAULT_TIMEOUT_SECONDS + ", max " + MAX_TIMEOUT_SECONDS + ". Ignored with background."));
        props.put("background", Map.of("type", "boolean",
                "description", "Start the command and return at once with its pid and log file, for a "
                        + "server or watcher that never exits. Default false."));
        return Map.of("type", "object", "properties", props, "required", new String[]{"command"});
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String command = (String) arguments.get("command");
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }
        if (Boolean.parseBoolean(String.valueOf(arguments.getOrDefault("background", "false")))) {
            return startInBackground(command);
        }
        if (arguments.get("timeout") == null && looksLongRunning(command)) {
            // The model reads a refusal; it does not read a description. A
            // server started in the foreground would sit here until the
            // timeout and then be killed — say so before it starts.
            return "Error: this looks like a server or watcher that keeps running, and it would be killed at "
                    + "the timeout. Start it with background=true (the call returns the pid and a log file), or "
                    + "pass an explicit timeout if it does exit on its own.";
        }
        int timeout = timeoutSeconds(arguments.get("timeout"));
        log.info("bash> {}", command);
        Process process = null;
        Thread drainer = null;
        Output output = new Output();
        try {
            process = new ProcessBuilder("bash", "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start();
            // Drain concurrently: waitFor before reading deadlocks on a full
            // pipe as soon as the command prints more than the OS buffers.
            Process p = process;
            drainer = Thread.ofVirtual().name("bash-output").start(() -> drain(p, output));
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                BackgroundProcesses.killTree(process.toHandle());
                drainer.join(2_000);
                log.warn("bash: timed out after {}s", timeout);
                return "Error: command timed out after " + timeout + " seconds"
                        + (output.isEmpty() ? "" : "\nOutput so far:\n" + output.text())
                        + "\n(A server or watcher that is meant to keep running must be started with background=true.)";
            }
            drainer.join();
            int exitCode = process.exitValue();
            log.info("bash exit={} ({} chars of output)", exitCode, output.length());
            String text = output.text();
            if (exitCode != 0) {
                return "Exit code " + exitCode + ":\n" + text;
            }
            return text.isEmpty() ? "(no output)" : text;
        } catch (InterruptedException ie) {
            // Cancel from the turn thread: re-set interrupt flag and bail out.
            Thread.currentThread().interrupt();
            log.info("bash: interrupted — terminating subprocess");
            return "Error: bash command cancelled";
        } catch (Exception e) {
            log.error("bash: error executing command: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } finally {
            // Guarantee no subprocess — nor anything it started — survives the
            // tool call, regardless of how we exited (interrupt, exception,
            // normal return). Only a background process is meant to outlive it.
            if (process != null && process.isAlive()) {
                BackgroundProcesses.killTree(process.toHandle());
                log.warn("bash: subprocess tree force-destroyed in finally");
            }
            if (drainer != null) drainer.interrupt();
        }
    }

    /** Starts the command detached from the call: output to a log file, the pid and the file back at once. */
    private String startInBackground(String command) {
        Path logFile;
        try {
            Path dir = Files.createDirectories(logDir());
            logFile = dir.resolve("bash-" + System.currentTimeMillis() + ".log");
            Files.writeString(logFile, "$ " + command + "\n");
        } catch (IOException e) {
            return "Error: cannot create the log file: " + e.getMessage();
        }
        log.info("bash (background)> {} -> {}", command, logFile);
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .start();
            BackgroundProcesses.register(sessionId, process, command, logFile);
            // Watch the first seconds: a server that dies on a build error dies
            // now, and the model must see that here — it does not go looking.
            long started = System.currentTimeMillis();
            boolean exited = process.waitFor(BACKGROUND_WATCH_MS, TimeUnit.MILLISECONDS);
            long after = System.currentTimeMillis() - started;
            StringBuilder out = new StringBuilder("Started in background: pid ").append(process.pid())
                    .append("\nLog: ").append(logFile);
            if (exited) {
                out.append("\nStatus: EXITED after ").append(after).append(" ms with code ")
                        .append(process.exitValue()).append(" — it is not running.");
            } else {
                out.append("\nStatus: still running after ").append(after).append(" ms.");
            }
            String tail = logTail(logFile);
            out.append("\n--- log so far ---\n").append(tail.isBlank() ? "(nothing printed yet)" : tail);
            out.append("\n--- end of log ---\n");
            out.append(exited
                    ? "Read the errors above and fix them before starting again."
                    : "Check the log again with `tail -n 50 " + logFile + "` before telling the user it is up; "
                    + "a listening server prints its URL. End it with process_kill(pid=" + process.pid() + ").");
            return out.toString();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: bash command cancelled";
        }
    }

    /** The last {@link #BACKGROUND_LOG_LINES} lines of a log, the command echo dropped. */
    private static String logTail(Path logFile) {
        try {
            java.util.List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).startsWith("$ ")) lines = lines.subList(1, lines.size());
            int from = Math.max(0, lines.size() - BACKGROUND_LOG_LINES);
            String text = String.join("\n", lines.subList(from, lines.size()));
            return text.length() > 6_000 ? text.substring(text.length() - 6_000) : text;
        } catch (IOException e) {
            return "(log not readable: " + e.getMessage() + ")";
        }
    }

    /**
     * Where a background process logs: the session's own directory when the
     * session works in it or keeps it reachable — its {@code logs/} — else a
     * temp directory of the runtime. The session's directory is the root
     * named after the session id.
     */
    private Path logDir() {
        if (roots != null && sessionId != null) {
            String own = sessionId.value();
            for (Path root : java.util.stream.Stream.concat(java.util.stream.Stream.of(roots.base()),
                    roots.extra().stream()).toList()) {
                if (root.getFileName() != null && root.getFileName().toString().equals(own)) {
                    return root.resolve("logs");
                }
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "mindconnect-bash",
                sessionId == null ? "no-session" : sessionId.value());
    }

    private static void drain(Process process, Output output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        } catch (IOException e) {
            // The process was killed under us; what was read stays.
        }
    }

    /** Commands that, by name, keep running until killed: dev servers, watchers, log followers. */
    private static final java.util.regex.Pattern LONG_RUNNING = java.util.regex.Pattern.compile(
            "(^|[;&|]\\s*)(\\S*/)?("
            + "(npm|pnpm|yarn|bun)\\s+(run\\s+)?(dev|start|serve|watch|preview)\\b"
            + "|(npx\\s+)?(vite|next\\s+dev|nodemon|webpack(-dev-server)?\\s+(serve|--watch)|ng\\s+serve|nuxt\\s+dev|astro\\s+dev)\\b"
            + "|mvn\\b[^;&|]*\\bspring-boot:run\\b"
            + "|(gradle|gradlew)\\b[^;&|]*\\b(bootRun|run)\\b"
            + "|docker(-compose|\\s+compose)\\s+up\\b(?![^;&|]*\\s-d\\b)"
            + "|python3?\\s+-m\\s+http\\.server\\b"
            + "|(flask|uvicorn|gunicorn|django-admin|manage\\.py)\\s+(run|runserver)?\\b"
            + "|rails\\s+(s|server)\\b"
            + "|tail\\s+-[a-zA-Z]*[fF]\\b"
            + "|watch\\s"
            + ")");

    /** Does the command look like one that never returns on its own? Only a guess — an explicit timeout overrides it. */
    static boolean looksLongRunning(String command) {
        return LONG_RUNNING.matcher(command.strip()).find();
    }

    static int timeoutSeconds(Object raw) {
        if (raw == null) return DEFAULT_TIMEOUT_SECONDS;
        try {
            int v = raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
            return v <= 0 ? DEFAULT_TIMEOUT_SECONDS : Math.min(v, MAX_TIMEOUT_SECONDS);
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /** The command's output as it arrives: the first {@link #MAX_OUTPUT_CHARS} kept, the rest counted. */
    static final class Output {
        private final StringBuilder kept = new StringBuilder();
        private long droppedLines;
        private long droppedChars;

        synchronized void add(String line) {
            if (kept.length() + line.length() + 1 <= MAX_OUTPUT_CHARS) {
                if (kept.length() > 0) kept.append('\n');
                kept.append(line);
            } else {
                droppedLines++;
                droppedChars += line.length() + 1;
            }
        }

        synchronized boolean isEmpty() {
            return kept.length() == 0 && droppedLines == 0;
        }

        synchronized int length() {
            return kept.length();
        }

        synchronized String text() {
            if (droppedLines == 0) return kept.toString();
            return kept + "\n\n[output cut after " + MAX_OUTPUT_CHARS + " chars — " + droppedLines
                    + " more line(s), " + droppedChars + " chars, not shown; pipe through head, tail or grep]";
        }
    }
}
