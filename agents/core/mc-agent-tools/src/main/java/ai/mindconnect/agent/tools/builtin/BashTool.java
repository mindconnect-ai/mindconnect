package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs a shell command in the session's working directory and returns
 * what it printed. Output is drained while the command runs — a command
 * that prints more than the pipe holds used to block on a full pipe until
 * it was declared timed out — capped at {@link #MAX_OUTPUT_CHARS}, the
 * head kept (cut inside a line if need be) and the tail summarised. The
 * caller may set a timeout up to {@link #MAX_TIMEOUT_SECONDS}; a command
 * still running at the deadline is killed — the whole process tree, not
 * just the shell — and what it printed so far comes back with the error.
 * The call ends with the shell: a process it started that still holds the
 * output open gets {@link #OUTPUT_GRACE_MS}, then is killed if it can be
 * reached, and the call returns what was read either way.
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
    /** How long output may keep coming after the shell exited — from a process it started that inherited the pipe. */
    static final long OUTPUT_GRACE_MS = 2_000;
    /** The longest pause between two looks at the processes a command started. */
    private static final long DESCENDANT_POLL_MAX_MS = 1_000;
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
                + "with process_kill. Never detach a command yourself with `&`, `nohup` or `setsid`: the "
                + "process would run on out of reach, so such a command is refused." + extra;
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
        String detaching = detaches(command);
        if (detaching != null) {
            // The shell exits, the process it let go does not: nothing here
            // waits for it, logs it or can end it — with background=true
            // neither, since that tracks the shell. Refused before it starts.
            return "Error: " + detaching + " would leave the process running on its own, outside this call — "
                    + "no pid, no log, and process_kill could not end it. Start it with background=true instead "
                    + "(the call returns the pid and a log file). Jobs that finish by themselves may use & as "
                    + "long as the command waits for them.";
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
        // The processes the command started, as seen while the shell ran. One
        // its parent let go of is re-parented and no descendant any more, but
        // its handle still reaches it.
        Set<ProcessHandle> started = new LinkedHashSet<>();
        try {
            process = new ProcessBuilder("bash", "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start();
            // Drain concurrently: waitFor before reading deadlocks on a full
            // pipe as soon as the command prints more than the OS buffers.
            Process p = process;
            drainer = Thread.ofVirtual().name("bash-output").start(() -> drain(p, output));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            boolean finished = waitForShell(process, deadline, started);
            if (!finished) {
                BackgroundProcesses.killTree(process.toHandle());
                killLeftovers(started);
                drainer.join(2_000);
                log.warn("bash: timed out after {}s", timeout);
                return "Error: command timed out after " + timeout + " seconds"
                        + (output.isEmpty() ? "" : "\nOutput so far:\n" + output.text())
                        + "\n(A server or watcher that is meant to keep running must be started with background=true.)";
            }
            // The output ends when the last process holding the pipe closes it
            // — not always the shell: a child that inherited it and runs on
            // would hold the call as long as it lives. It gets a grace period,
            // never past the deadline; the drainer is then no longer waited for.
            String note = "";
            long left = Math.max(0, deadline - System.nanoTime());
            if (!drainer.join(Duration.ofNanos(Math.min(left, TimeUnit.MILLISECONDS.toNanos(OUTPUT_GRACE_MS))))) {
                int killed = killLeftovers(started);
                boolean closed = drainer.join(Duration.ofMillis(500));
                log.warn("bash: output still held open after the shell exited — {} process(es) killed, output {}",
                        killed, closed ? "closed" : "abandoned");
                note = "(A process the command started kept the output open after the shell exited — "
                        + (killed > 0 ? "it was killed." : "it could not be reached and may still be running.")
                        + " Start a process meant to keep running with background=true.)";
            }
            int exitCode = process.exitValue();
            log.info("bash exit={} ({} chars of output)", exitCode, output.length());
            String text = output.text();
            if (!note.isEmpty()) text = text.isEmpty() ? note : text + "\n" + note;
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
            // A drainer still reading means something still holds the output:
            // on cancel or error, what the command started goes too. Once the
            // output has ended, a daemon a tool started for itself is left be.
            if (drainer != null && drainer.isAlive()) {
                killLeftovers(started);
                drainer.interrupt();
            }
        }
    }

    /**
     * Waits for the shell until the deadline, noting on the way every process
     * it started — looked at often at first, then less often, so a long build
     * does not scan the process table many times a second.
     */
    private static boolean waitForShell(Process process, long deadline, Set<ProcessHandle> started)
            throws InterruptedException {
        long pollMs = 50;
        while (true) {
            try (Stream<ProcessHandle> descendants = process.descendants()) {
                descendants.forEach(started::add);
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) return !process.isAlive();
            if (process.waitFor(Math.min(left, TimeUnit.MILLISECONDS.toNanos(pollMs)), TimeUnit.NANOSECONDS)) {
                return true;
            }
            pollMs = Math.min(pollMs * 2, DESCENDANT_POLL_MAX_MS);
        }
    }

    /** Kills those of the noted processes still alive, with what they started; returns how many were. */
    private static int killLeftovers(Set<ProcessHandle> started) {
        int killed = 0;
        for (ProcessHandle handle : started) {
            if (handle.isAlive()) {
                BackgroundProcesses.killTree(handle);
                killed++;
            }
        }
        return killed;
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

    /** Reads the output in chunks, not lines: a line without end must not grow in memory before the cap sees it. */
    private static void drain(Process process, Output output) {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] chunk = new char[8_192];
            int read;
            while ((read = reader.read(chunk)) != -1) {
                output.add(chunk, 0, read);
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

    /**
     * How the command would let a process go on without it, or {@code null}:
     * a {@code &} that nothing {@code wait}s for, or {@code nohup},
     * {@code setsid} or {@code disown} where a command starts. Such a process
     * outlives the shell this tool watches. Quoted text, {@code &&},
     * {@code |&} and redirections such as {@code 2>&1} and {@code &>} do not
     * detach anything, nor does a {@code #} comment or the body of a
     * here-document — a page written with {@code cat <<'EOF'} may say
     * "Tom &amp; Jerry" or "don't". Read off the text, not parsed — when in
     * doubt it lets the command through.
     */
    static String detaches(String command) {
        boolean inSingle = false, inDouble = false, escaped = false;
        boolean loneAmpersand = false, waits = false, commandPosition = true;
        String starter = null;
        StringBuilder word = new StringBuilder();
        // Here-documents whose bodies start after the next line break.
        List<Heredoc> heredocs = new ArrayList<>();
        // Inside $(( … )) a << is a shift, not a here-document.
        int arithmetic = 0;
        int n = command.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? command.charAt(i) : '\n';
            if (escaped) {
                escaped = false;
                word.append(c);
                continue;
            }
            if (inSingle) {
                if (c == '\'') inSingle = false; else word.append(c);
                continue;
            }
            if (inDouble) {
                if (c == '\\') escaped = true;
                else if (c == '"') inDouble = false;
                else word.append(c);
                continue;
            }
            if (c == '\\') { escaped = true; continue; }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '#' && word.length() == 0 && (i == 0 || isWordBoundary(command.charAt(i - 1)))) {
                // A comment runs to the end of the line; its line break still separates.
                int end = command.indexOf('\n', i);
                i = (end < 0 ? n : end) - 1;
                continue;
            }

            char prev = i > 0 && i <= n ? command.charAt(i - 1) : ' ';
            char next = i + 1 < n ? command.charAt(i + 1) : ' ';
            if (c == '(' && next == '(') arithmetic++;
            else if (c == ')' && prev == ')' && arithmetic > 0) arithmetic--;

            boolean separator = c == ';' || c == '|' || c == '(' || c == ')' || c == '\n';
            if (c == '&') {
                boolean redirect = prev == '>' || prev == '<' || next == '>';
                if (!redirect) {
                    separator = true;
                    if (prev != '&' && prev != '|' && next != '&') loneAmpersand = true;
                }
            }
            if (separator || Character.isWhitespace(c) || c == '<' || c == '>' || c == '&') {
                if (word.length() > 0) {
                    String w = word.toString();
                    if (w.equals("wait")) waits = true;
                    if (commandPosition && starter == null
                            && (w.equals("nohup") || w.equals("setsid") || w.equals("disown"))) {
                        starter = "`" + w + "`";
                    }
                    commandPosition = false;
                    word.setLength(0);
                }
                if (separator) commandPosition = true;
                if (c == '<' && next == '<' && prev != '<' && arithmetic == 0
                        && (i + 2 >= n || command.charAt(i + 2) != '<')) {
                    // <<DELIM, <<-DELIM, <<'DELIM' — but not the here-string <<<.
                    i = readHeredocDelimiter(command, i + 2, heredocs) - 1;
                } else if (c == '\n' && i < n && !heredocs.isEmpty()) {
                    // The bodies are text, whatever they say; the command goes on after the last delimiter line.
                    i = skipHeredocBodies(command, i + 1, heredocs) - 1;
                    heredocs.clear();
                }
                continue;
            }
            word.append(c);
        }
        if (starter != null) return starter;
        return loneAmpersand && !waits ? "`&`" : null;
    }

    /** A here-document waiting for its body: the line that ends it, and whether leading tabs are stripped ({@code <<-}). */
    private record Heredoc(String delimiter, boolean stripTabs) {
    }

    private static boolean isWordBoundary(char c) {
        return Character.isWhitespace(c) || ";&|()<>".indexOf(c) >= 0;
    }

    /** Reads the delimiter after {@code <<}, quotes removed, and notes the here-document; returns where the command goes on. */
    private static int readHeredocDelimiter(String command, int from, List<Heredoc> heredocs) {
        int n = command.length(), j = from;
        boolean stripTabs = j < n && command.charAt(j) == '-';
        if (stripTabs) j++;
        while (j < n && (command.charAt(j) == ' ' || command.charAt(j) == '\t')) j++;
        StringBuilder delimiter = new StringBuilder();
        while (j < n) {
            char d = command.charAt(j);
            if (d == '\'' || d == '"') {
                int close = command.indexOf(d, j + 1);
                if (close < 0) close = n;
                delimiter.append(command, j + 1, close);
                j = Math.min(close + 1, n);
            } else if (d == '\\' && j + 1 < n) {
                delimiter.append(command.charAt(j + 1));
                j += 2;
            } else if (isWordBoundary(d)) {
                break;
            } else {
                delimiter.append(d);
                j++;
            }
        }
        if (delimiter.length() > 0) heredocs.add(new Heredoc(delimiter.toString(), stripTabs));
        return j;
    }

    /**
     * Skips the bodies of the noted here-documents, in order, from the start
     * of the line after their operators; a body ends at a line equal to its
     * delimiter. Returns where the command goes on — its end, for a body
     * never closed.
     */
    private static int skipHeredocBodies(String command, int from, List<Heredoc> heredocs) {
        int n = command.length(), pos = from;
        for (Heredoc heredoc : heredocs) {
            while (pos < n) {
                int end = command.indexOf('\n', pos);
                if (end < 0) end = n;
                String line = command.substring(pos, end);
                pos = Math.min(end + 1, n);
                if (heredoc.stripTabs()) line = line.replaceFirst("^\t+", "");
                if (line.equals(heredoc.delimiter())) break;
            }
        }
        return pos;
    }

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

    /**
     * The command's output as it arrives: the first {@link #MAX_OUTPUT_CHARS}
     * characters kept — cut inside a line when the cap falls there — the rest
     * counted, never held. Line breaks come out as {@code \n}, and the last
     * one is dropped, as reading line by line did.
     */
    static final class Output {
        private final StringBuilder kept = new StringBuilder();
        private boolean full;
        private long droppedLines;
        private long droppedChars;
        /** A line break read but not yet written: the output's last one never is. */
        private boolean pendingBreak;
        /** The previous character was a {@code \r}; a {@code \n} right after it is the same break. */
        private boolean afterCr;
        /** Whether the current line has characters kept, and whether it has characters dropped. */
        private boolean lineKept, lineDropped;

        synchronized void add(char[] chars, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                char c = chars[i];
                if (c == '\n' && afterCr) {
                    afterCr = false;
                    continue;
                }
                afterCr = c == '\r';
                if (c == '\r' || c == '\n') {
                    if (pendingBreak) put('\n');
                    pendingBreak = true;
                } else {
                    if (pendingBreak) put('\n');
                    pendingBreak = false;
                    put(c);
                }
            }
        }

        private void put(char c) {
            // A surrogate pair is kept whole or not at all.
            int room = Character.isHighSurrogate(c) ? 2 : 1;
            if (!full && kept.length() + room <= MAX_OUTPUT_CHARS) {
                kept.append(c);
                lineKept = c != '\n';
                if (c == '\n') lineDropped = false;
                return;
            }
            full = true;
            droppedChars++;
            if (c != '\n') {
                lineDropped = true;
                return;
            }
            // A line counts as not shown unless all of it was kept and only its break fell past the cap.
            if (lineDropped || !lineKept) droppedLines++;
            lineKept = false;
            lineDropped = false;
        }

        synchronized boolean isEmpty() {
            return kept.length() == 0 && droppedChars == 0;
        }

        synchronized int length() {
            return kept.length();
        }

        synchronized String text() {
            if (droppedChars == 0) return kept.toString();
            long lines = droppedLines + (lineDropped ? 1 : 0);
            return kept + "\n\n[output cut after " + MAX_OUTPUT_CHARS + " chars — " + lines
                    + " more line(s), " + droppedChars + " chars, not shown; pipe through head, tail or grep]";
        }
    }
}
