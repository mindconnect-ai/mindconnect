package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tools.code.CodeLanguages.CodeLanguage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code code_execute} tool: runs a program in the session's isolated
 * container. The result is plain sectioned text — exit code, stdout, stderr —
 * which LLMs handle more reliably than JSON-escaped program output.
 */
public final class CodeExecuteTool implements Tool {

    private final CodeExecutionService service;
    private final Map<String, CodeLanguage> languages;
    private final String sessionKey;
    /** Container network mode: {@code none} (default) or {@code bridge}. */
    private final String network;
    /** Host directory visible inside the container, or {@code null} for none. */
    private final HostMount mount;
    /** The chat's directories, mounted under their host paths; the working directory also at {@code /workspace}. */
    private final SessionDirs dirs;
    /** How a program reaches the agent's other tools, or null when it cannot. */
    private final ToolBridge bridge;

    /**
     * What it takes to let a program call tools: who runs them, in whose
     * scope, how long the host may take and how often its directory is read.
     *
     * @param invoker      the host side — the sandbox never sees a user or a credential
     * @param scope        the call's scope, from which the invoker takes the session
     * @param sessionKey   names the exchange directory together with a fresh id
     * @param ceiling      the wall clock a run may reach once waiting is not counted
     * @param pollInterval how often the exchange directory is looked at
     */
    public record ToolBridge(ScopedToolInvoker invoker, ToolCallScope scope, String sessionKey,
                             Duration ceiling, Duration pollInterval) { }

    public CodeExecuteTool(CodeExecutionService service, Map<String, CodeLanguage> languages,
                           String sessionKey, String network) {
        this(service, languages, sessionKey, network, null);
    }

    public CodeExecuteTool(CodeExecutionService service, Map<String, CodeLanguage> languages,
                           String sessionKey, String network, HostMount mount) {
        this(service, languages, sessionKey, network, mount, SessionDirs.none());
    }

    public CodeExecuteTool(CodeExecutionService service, Map<String, CodeLanguage> languages,
                           String sessionKey, String network, HostMount mount, SessionDirs dirs) {
        this(service, languages, sessionKey, network, mount, dirs, null);
    }

    /** With a bridge, the code may call the agent's other tools; without one it may not. */
    public CodeExecuteTool(CodeExecutionService service, Map<String, CodeLanguage> languages,
                           String sessionKey, String network, HostMount mount, SessionDirs dirs,
                           ToolBridge bridge) {
        this.mount = mount;
        this.dirs = dirs == null ? SessionDirs.none() : dirs;
        this.service = service;
        this.languages = languages;
        this.sessionKey = sessionKey;
        this.network = network;
        this.bridge = bridge;
    }

    @Override
    public String name() {
        return "code_execute";
    }

    /**
     * What the model needs to write a program that works the first time:
     * which interpreters, what is installed, where files go, what survives a
     * call and what the limits are. Built from the actual settings, so a
     * binding must not replace it with a fixed text.
     */
    @Override
    public String description() {
        return "Runs a program in a container and returns its exit code, stdout and stderr. "
                + languagesNote()
                + networkNote()
                + filesNote()
                + mountNote()
                + toolsNote()
                + lifecycleNote();
    }

    /**
     * The interpreters and their images — what is installed is what the image
     * ships, so the model is told what that is where it is known, and warned
     * off packages where it is not.
     */
    private String languagesNote() {
        String each = String.join("; ", languages.values().stream()
                .map(l -> l.name() + " (image " + l.image()
                        + (l.contents() == null ? "" : ": " + l.contents()) + ")")
                .toList());
        return "The program is passed as code and read from stdin: " + each + ". Nothing else is installed; "
                + "an image described by name only may carry just its standard library. ";
    }

    private String networkNote() {
        return "none".equals(network)
                ? "There is no network: no HTTP requests, no pip or npm installs. "
                : "Network is on: HTTP requests work, and so do pip and npm installs. ";
    }

    /**
     * Which of the chat's directories the code sees, and where — so it writes
     * where the user looks and names the path they will find.
     */
    private String filesNote() {
        Path working = dirs.workingDir();
        if (working == null) {
            return "The current directory is /workspace, private to this chat; files there stay between "
                    + "calls, but nobody else can open them. ";
        }
        StringBuilder note = new StringBuilder("The current directory is the chat's working directory ")
                .append(working).append(", mounted writable under that same path (also as /workspace)");
        if (!dirs.additionalDirs().isEmpty()) {
            note.append("; so are the chat's other directories, each under its own path: ")
                    .append(String.join(", ", dirs.additionalDirs().stream().map(Path::toString).toList()));
        }
        return note.append(". Paths mean the same here as for the other file tools. Save a file the user "
                        + "should get there and give them its full path, e.g. ")
                .append(working.resolve("report.pptx")).append(". ").toString();
    }

    /**
     * The point of the whole detour, told to the model in the one place it
     * will read: many calls in one program cost one result instead of many,
     * and what the program filters out never reaches the context at all.
     * Silent when no bridge is bound — an instruction for a thing that is not
     * there is worse than none.
     */
    private String toolsNote() {
        List<String> callable = callableTools();
        if (callable.isEmpty()) {
            return "";
        }
        return "This session's other tools can be called FROM the program: in Python, "
                + "`import mc_tools` and `mc_tools.call(\"<name>\", {\"arg\": ...})`, which returns the "
                + "same text you would get as a tool result and raises mc_tools.ToolError when the "
                + "tool fails. Name every tool the program calls in the 'tools' argument; anything "
                + "else is refused. Available: " + String.join(", ", callable) + ". Use this to make "
                + "many calls at once, to loop over results, or to filter a large result down before "
                + "it reaches you — only what the program prints costs you context. ";
    }

    /** What survives a call, and the limits a call runs under. */
    private String lifecycleNote() {
        var settings = service.settings();
        return "Every call is a fresh process: variables do not carry over, files do. The container is "
                + "recreated after " + minutes(settings.idleTimeout()) + " without a call and when the chat's "
                + "directories change, and installed packages go with it. A call may run "
                + settings.execTimeout().toSeconds() + " seconds with " + settings.memory() + " of memory; "
                + "one that runs longer is stopped.";
    }

    private static String minutes(java.time.Duration duration) {
        long minutes = duration.toMinutes();
        return minutes >= 1 ? minutes + (minutes == 1 ? " minute" : " minutes")
                : duration.toSeconds() + " seconds";
    }

    /**
     * The model can only use the host directory if it is told it exists, and
     * told where — the mount is invisible from inside otherwise.
     */
    private String mountNote() {
        if (mount == null) {
            return "";
        }
        return "The host directory " + mount.dir() + " is also mounted at " + HostMount.MOUNT_POINT
                + (mount.readOnly() ? ", read-only: read files from there, do not save any. "
                        : ", writable. ");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> language = new LinkedHashMap<>();
        language.put("type", "string");
        language.put("enum", List.copyOf(languages.keySet()));
        language.put("description", "Language to run the code with.");
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("type", "string");
        code.put("description", "The complete program to execute (read from stdin by the interpreter).");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("language", language);
        properties.put("code", code);
        // Offered only when there is something to reach: an argument for an
        // empty list is an invitation to get it wrong.
        if (!callableTools().isEmpty()) {
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("type", "array");
            tools.put("items", Map.of("type", "string"));
            tools.put("description", "Names of this session's tools the program calls through "
                    + "mc_tools. A tool that is not listed here is refused at the call. Leave it out "
                    + "when the program calls no tools.");
            properties.put("tools", tools);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("language", "code"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String languageName = String.valueOf(arguments.get("language")).toLowerCase();
        CodeLanguage language = languages.get(languageName);
        if (language == null) {
            return "Error: unknown language '" + languageName + "'. Available: "
                    + String.join(", ", languages.keySet());
        }
        Object code = arguments.get("code");
        if (!(code instanceof String source) || source.isBlank()) {
            return "Error: 'code' must be a non-empty string.";
        }
        List<String> declared = declaredTools(arguments);
        if (bridge == null && !declared.isEmpty()) {
            return "Error: this installation cannot call tools from code. Run the program without "
                    + "the 'tools' argument, and call the tools yourself.";
        }
        // No declaration, no exchange: the list is what a human approved, so a
        // program that named nothing gets no way in.
        if (bridge == null || declared.isEmpty()) {
            return run(language, source, CodeExecutionService.ExecOptions.none());
        }
        List<String> callable = callableTools();
        List<String> unknown = declared.stream().filter(tool -> !callable.contains(tool)).toList();
        if (!unknown.isEmpty()) {
            return "Error: not a tool of this session: " + String.join(", ", unknown)
                    + ". Available: " + String.join(", ", callable);
        }
        Path hostWorkspace = service.workspaceHostDir(sessionKey, dirs);
        // Inside the container the working directory keeps its own path; a
        // session without one sees the scratch directory as /workspace.
        Path programWorkspace = dirs.workingDir() != null
                ? dirs.workingDir() : Path.of(SessionDirs.WORKSPACE);
        try (ToolExchange exchange = ToolExchange.open(
                WorkspaceFiles.local(FileRoots.of(hostWorkspace)), hostWorkspace, programWorkspace,
                execId(), bridge.invoker(), bridge.scope(), name(), declared, bridge.pollInterval())) {
            CodeExecutionService.ExecOptions options = new CodeExecutionService.ExecOptions(
                    exchange.env(), exchange::requestPending, bridge.ceiling());
            String output = run(language, source, options);
            // Only now: the exchange fills its list while the program runs.
            return output + toolCallsNote(exchange.calls());
        } catch (java.io.IOException e) {
            return "Error: the tool exchange could not be opened: " + e.getMessage();
        }
    }

    /** What a program may call here: nothing without a bridge. */
    private List<String> callableTools() {
        return bridge == null ? List.of() : bridge.invoker().callableTools(bridge.scope(), name());
    }

    /** The {@code tools} argument, as names; anything that is not a string is ignored. */
    private static List<String> declaredTools(Map<String, Object> arguments) {
        Object raw = arguments.get("tools");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String name && !name.isBlank() && !names.contains(name.trim())) {
                names.add(name.trim());
            }
        }
        return List.copyOf(names);
    }

    /** One run per exchange directory, so two programs in one container cannot mix. */
    private static String execId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Runs the program and renders what came back. */
    private String run(CodeLanguage language, String source, CodeExecutionService.ExecOptions options) {
        CodeExecutionService.ExecResult result;
        try {
            result = service.execute(sessionKey, language, network, mount, dirs, source, options);
        } catch (RuntimeException e) {
            return "Error: code execution failed: " + e.getMessage();
        }
        if (result.timedOut()) {
            return "Error: execution timed out after " + result.tookMs() + " ms. "
                    + "The session container was reset; files in /workspace are still there, "
                    + "but installed packages are gone.";
        }
        StringBuilder out = new StringBuilder();
        out.append("exit code: ").append(result.exitCode())
                .append(" (").append(result.tookMs()).append(" ms)\n");
        out.append("--- stdout ---\n").append(result.stdout());
        if (!result.stdout().endsWith("\n") && !result.stdout().isEmpty()) {
            out.append('\n');
        }
        if (!result.stderr().isBlank()) {
            out.append("--- stderr ---\n").append(result.stderr());
        }
        return out.toString();
    }

    /**
     * What the program called, in one line. The results themselves stay out —
     * keeping them out of the context is why the program ran at all — but
     * which tools ran, and whether they worked, is something the model has to
     * know to make sense of the output it did get.
     */
    private static String toolCallsNote(List<ToolExchange.Call> calls) {
        if (calls.isEmpty()) {
            return "";
        }
        StringBuilder note = new StringBuilder("\n--- tools called ---\n");
        for (ToolExchange.Call call : calls) {
            note.append(call.tool()).append(call.ok() ? "" : " (failed)")
                    .append(" ").append(call.durationMs()).append(" ms\n");
        }
        return note.toString();
    }
}
