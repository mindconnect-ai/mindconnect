package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.WorkspaceEntry;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The way a sandboxed program reaches the agent's tools: a directory.
 *
 * <p>The program writes {@code 0001.request.json} and waits for
 * {@code 0001.response.json}; this class, on the host, notices the request,
 * runs the tool through the {@link ScopedToolInvoker} — with the user, the
 * session and the credentials the sandbox never sees — and writes the answer
 * back. Nothing else crosses the boundary: no socket, no port, no token, and
 * with the default container network mode, no network at all.
 *
 * <p><b>Why a directory.</b> It is the one channel that exists in every
 * variant: locally the directory is a bind mount and this class reads it
 * straight off the disk; remotely the very same code runs against the
 * workspace server's {@link WorkspaceFiles}, so no second protocol and no new
 * endpoint. It needs nothing of the image but the ability to write a file,
 * which makes {@code mc_tools} twenty lines in any language — and {@code ls}
 * is the debugger.
 *
 * <p><b>Why a marker file.</b> {@link WorkspaceFiles} can write and list; it
 * cannot rename, so the usual write-temp-then-rename is not available, and a
 * reader could otherwise pick up half a file. Each side therefore writes its
 * JSON first and an empty {@code .ready} beside it second: the marker exists
 * only once the content is fully written, so "ready and readable" is one
 * observation instead of two.
 *
 * <p>Requests are answered on a thread of this exchange's own, because the
 * call that started the program is blocked until the program ends. Everything
 * it did is on {@link #calls()} afterwards — for the metadata of the outer
 * tool result, never for the model's context, which is the entire point of
 * the detour.
 */
public final class ToolExchange implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolExchange.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The exchange lives under this name in the workspace; the file tools skip it. */
    public static final String DIR_NAME = ".mc-tools";

    /** The program is told where its directory is under this name. */
    public static final String DIR_ENV = "MC_TOOLS_DIR";

    /** Big enough for any answer a program should hand back, small enough to stay out of trouble. */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final String REQUEST_SUFFIX = ".request.json";
    private static final String RESPONSE_SUFFIX = ".response.json";
    private static final String READY_SUFFIX = ".ready";

    /** One call a program made, for the outer tool's metadata. */
    public record Call(String tool, boolean ok, long durationMs) { }

    private final WorkspaceFiles files;
    private final Path dir;
    /** The same directory as the program knows it; differs when /workspace is a scratch directory. */
    private final Path programDir;
    private final ScopedToolInvoker invoker;
    private final ToolCallScope scope;
    private final String callerToolName;
    /** What the program declared it would call; empty means it declared nothing. */
    private final Set<String> declared;
    private final Duration pollInterval;

    private final Map<String, Boolean> answered = new ConcurrentHashMap<>();
    private final List<Call> calls = new ArrayList<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread poller;

    private ToolExchange(WorkspaceFiles files, Path dir, Path programDir, ScopedToolInvoker invoker,
                         ToolCallScope scope, String callerToolName, Collection<String> declared,
                         Duration pollInterval) {
        this.files = files;
        this.dir = dir;
        this.programDir = programDir;
        this.invoker = invoker;
        this.scope = scope;
        this.callerToolName = callerToolName;
        this.declared = declared == null ? Set.of() : new LinkedHashSet<>(declared);
        this.pollInterval = pollInterval;
    }

    /**
     * Opens the exchange for one run of a program: creates its directory and
     * starts answering. {@code declared} is what the program said it would
     * call — a request for anything else is refused, because that list is
     * what the human approved.
     *
     * @param workspaceDir the directory {@link #DIR_NAME} goes under, usually the chat's own
     * @param execId       names this run's directory; one per run, so parallel runs cannot mix
     */
    public static ToolExchange open(WorkspaceFiles files, Path workspaceDir, String execId,
                                    ScopedToolInvoker invoker, ToolCallScope scope,
                                    String callerToolName, Collection<String> declared,
                                    Duration pollInterval) throws IOException {
        return open(files, workspaceDir, workspaceDir, execId, invoker, scope, callerToolName,
                declared, pollInterval);
    }

    /**
     * Same, where the program knows the workspace by another path than the
     * host does — a container whose {@code /workspace} is a scratch directory
     * of the host's. The host reads {@code hostWorkspaceDir}; the program is
     * told about {@code programWorkspaceDir}. They are the same directory.
     */
    public static ToolExchange open(WorkspaceFiles files, Path hostWorkspaceDir, Path programWorkspaceDir,
                                    String execId, ScopedToolInvoker invoker, ToolCallScope scope,
                                    String callerToolName, Collection<String> declared,
                                    Duration pollInterval) throws IOException {
        Path dir = hostWorkspaceDir.resolve(DIR_NAME).resolve(execId);
        Path programDir = programWorkspaceDir.resolve(DIR_NAME).resolve(execId);
        ToolExchange exchange = new ToolExchange(files, dir, programDir, invoker, scope, callerToolName,
                declared, pollInterval);
        // A workspace has no mkdir — writing a file makes the directories. It
        // doubles as the sign that the host is listening.
        files.write(dir.resolve("open"), new byte[0]);
        exchange.start();
        return exchange;
    }

    /** Where the host reads and writes; {@link #env()} is what the program is told. */
    public Path directory() {
        return dir;
    }

    /** The environment a program needs to find its exchange — in ITS path space. */
    public Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(DIR_ENV, programDir.toString());
        return env;
    }

    /**
     * Whether a request is being served right now. The caller stops its
     * execution clock while this holds: the program is not running, it is
     * waiting for the host, and the wait is the host's fault, not the
     * program's.
     */
    public boolean requestPending() {
        return pending.get() > 0;
    }

    /** What the program called, in order — for the outer result's metadata. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    // ── the loop ────────────────────────────────────────────────────────────

    private void start() {
        this.poller = Thread.ofVirtual().name("mc-tools-" + dir.getFileName()).start(this::loop);
    }

    private void loop() {
        while (!closed.get()) {
            try {
                pump();
            } catch (IOException e) {
                // A workspace that is briefly unreachable must not end the
                // exchange: the program is still running and will ask again.
                log.debug("Tool exchange {} could not be read: {}", dir, e.toString());
            } catch (RuntimeException e) {
                log.warn("Tool exchange {} failed unexpectedly", dir, e);
            }
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One pass: answer every request that is ready and not answered yet. */
    void pump() throws IOException {
        List<String> ready = new ArrayList<>();
        Set<String> markers = new LinkedHashSet<>();
        for (WorkspaceEntry entry : files.list(dir)) {
            String name = entry.name();
            if (name.endsWith(REQUEST_SUFFIX + READY_SUFFIX)) {
                markers.add(name.substring(0, name.length() - (REQUEST_SUFFIX + READY_SUFFIX).length()));
            }
        }
        for (String id : markers) {
            if (answered.putIfAbsent(id, Boolean.TRUE) == null) {
                ready.add(id);
            }
        }
        ready.sort(String::compareTo);   // the program numbers them; answer in that order
        for (String id : ready) {
            answer(id);
        }
    }

    private void answer(String id) throws IOException {
        pending.incrementAndGet();
        try {
            byte[] raw = files.readAllBytes(dir.resolve(id + REQUEST_SUFFIX));
            String tool;
            Map<String, Object> arguments;
            try {
                JsonNode root = MAPPER.readTree(raw);
                tool = root.path("tool").asText(null);
                JsonNode args = root.path("arguments");
                arguments = args.isObject() ? MAPPER.convertValue(args, Map.class) : Map.of();
            } catch (Exception e) {
                write(id, failure("Error: the request could not be read as JSON: " + e.getMessage()));
                record("?", false, 0L);
                return;
            }
            if (tool == null || tool.isBlank()) {
                write(id, failure("Error: the request names no tool."));
                record("?", false, 0L);
                return;
            }
            // The declaration is what the human saw and approved; a program
            // that reaches past it gets an answer it can handle, not a tool.
            if (!declared.isEmpty() && !declared.contains(tool)) {
                write(id, failure("Error: tool '" + tool + "' is not declared in the tools argument. "
                        + "Declared: " + String.join(", ", declared)));
                record(tool, false, 0L);
                return;
            }
            ScopedToolInvoker.Result result = invoker.invoke(scope, callerToolName, tool, arguments);
            write(id, response(result));
            record(tool, !result.failed(), result.durationMs());
        } finally {
            pending.decrementAndGet();
        }
    }

    private ObjectNode response(ScopedToolInvoker.Result result) {
        String output = result.output() == null ? "" : result.output();
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            // Whatever the program wanted with it, it did not want it through
            // a file this size — over REST it would be worse.
            return failure("Error: the result is " + bytes.length + " bytes, over the limit of "
                    + MAX_RESPONSE_BYTES + ". Ask the tool for less.");
        }
        ObjectNode node = MAPPER.createObjectNode();
        if (result.failed()) {
            node.put("ok", false);
            node.put("error", output);
        } else {
            node.put("ok", true);
            node.put("result", output);
        }
        return node;
    }

    private static ObjectNode failure(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", false);
        node.put("error", message);
        return node;
    }

    /** Content first, marker second — see the class comment. */
    private void write(String id, ObjectNode body) throws IOException {
        files.write(dir.resolve(id + RESPONSE_SUFFIX), MAPPER.writeValueAsBytes(body));
        files.write(dir.resolve(id + RESPONSE_SUFFIX + READY_SUFFIX), new byte[0]);
    }

    private synchronized void record(String tool, boolean ok, long durationMs) {
        calls.add(new Call(tool, ok, durationMs));
    }

    // ── the end ─────────────────────────────────────────────────────────────

    /**
     * Stops answering and takes the directory away. A workspace that cannot
     * delete keeps it: the run's id is unique, so what stays behind is stale,
     * never in the way.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Thread thread = poller;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            files.delete(dir);
        } catch (UnsupportedOperationException e) {
            log.debug("Workspace cannot delete {} — leaving the exchange directory behind", dir);
        } catch (IOException e) {
            log.debug("Could not remove the exchange directory {}: {}", dir, e.toString());
        }
    }
}
