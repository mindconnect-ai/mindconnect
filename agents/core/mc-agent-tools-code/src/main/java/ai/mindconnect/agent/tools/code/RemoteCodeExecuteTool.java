package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.CommandRunner;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code code_execute} in a workspace that lives elsewhere: the program goes to
 * the interpreter on stdin inside the session's container — the same container
 * {@code bash} uses, with the same {@code /workspace} — instead of a container
 * of its own. The template's image decides which interpreters exist; the
 * default ones are {@code python3} and {@code node}.
 */
public class RemoteCodeExecuteTool implements Tool {

    /** Interpreters that read a program from stdin. */
    static final Map<String, String> INTERPRETERS = Map.of("python", "python3 -", "node", "node -");

    private final CommandRunner runner;
    private final Duration timeout;
    /** How a program reaches the agent's other tools, or null when it cannot. */
    private final ToolBridge bridge;
    /** The timeout a run gets once it may wait for the host; the remote runner reports no progress. */
    private final Duration toolsTimeout;
    private final Duration pollInterval;

    /**
     * What it takes to let a program in a remote workspace call tools. The
     * exchange directory lives in that workspace and is read through its
     * {@code WorkspaceFiles} — the same class as locally, over the provider's
     * REST API instead of the disk.
     */
    public record ToolBridge(ScopedToolInvoker invoker, ToolCallScope scope,
                             Function<FileRoots, WorkspaceFiles> files) { }

    public RemoteCodeExecuteTool(CommandRunner runner, Duration timeout) {
        this(runner, timeout, null, timeout, Duration.ofMillis(250));
    }

    public RemoteCodeExecuteTool(CommandRunner runner, Duration timeout, ToolBridge bridge,
                                 Duration toolsTimeout, Duration pollInterval) {
        this.runner = runner;
        this.timeout = timeout;
        this.bridge = bridge;
        this.toolsTimeout = toolsTimeout == null ? timeout : toolsTimeout;
        this.pollInterval = pollInterval == null ? Duration.ofMillis(250) : pollInterval;
    }

    @Override
    public String name() {
        return "code_execute";
    }

    @Override
    public String description() {
        return "Executes a program in this session's own Linux container and returns its exit code and output. "
                + "Languages: " + String.join(", ", languages()) + ". The working directory "
                + runner.workingDirectory() + " holds the session's files — the same ones bash and the file "
                + "tools see — and persists for the session. Each call runs a fresh interpreter process, so "
                + "variables do NOT carry over between calls: write files to " + runner.workingDirectory()
                + " to pass data along. Packages installed with pip or npm land in the workspace and stay "
                + "for this session when the template allows network access." + toolsNote();
    }

    /**
     * The same offer as locally, worded the same way, so a model does not have
     * to learn two tools. Silent without a bridge.
     */
    private String toolsNote() {
        List<String> callable = callableTools();
        if (callable.isEmpty()) {
            return "";
        }
        return " This session's other tools can be called FROM the program: in Python, "
                + "`import mc_tools` and `mc_tools.call(\"<name>\", {\"arg\": ...})`, which returns the "
                + "same text you would get as a tool result. Name every tool the program calls in the "
                + "'tools' argument; anything else is refused. Available: "
                + String.join(", ", callable) + ". Use this to bundle many calls, or to filter a large "
                + "result before it reaches you — only what the program prints costs you context.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> language = new LinkedHashMap<>();
        language.put("type", "string");
        language.put("enum", languages());
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
        String interpreter = INTERPRETERS.get(languageName);
        if (interpreter == null) {
            return "Error: unknown language '" + languageName + "'. Available: " + String.join(", ", languages());
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
        if (bridge == null || declared.isEmpty()) {
            return run(interpreter, source, Map.of(), timeout);
        }
        List<String> callable = callableTools();
        List<String> unknown = declared.stream().filter(tool -> !callable.contains(tool)).toList();
        if (!unknown.isEmpty()) {
            return "Error: not a tool of this session: " + String.join(", ", unknown)
                    + ". Available: " + String.join(", ", callable);
        }
        Path workspace = Path.of(runner.workingDirectory());
        try (ToolExchange exchange = ToolExchange.open(
                bridge.files().apply(FileRoots.of(workspace)), workspace, execId(),
                bridge.invoker(), bridge.scope(), name(), declared, pollInterval)) {
            // The remote runner reports no progress, so the clock cannot be
            // stopped while the host works — the run gets the longer one instead.
            String output = run(interpreter, source, exchange.env(), toolsTimeout);
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

    private static String execId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String run(String interpreter, String source, Map<String, String> env, Duration runTimeout) {
        CommandRunner.Result result;
        try {
            result = runner.run(interpreter, source, env, runTimeout);
        } catch (CommandRunner.CommandException e) {
            return "Error: code execution failed: " + e.getMessage();
        }
        if (result.timedOut()) {
            return "Error: execution timed out after " + result.durationMs() + " ms. "
                    + "Files in " + runner.workingDirectory() + " are still there.";
        }
        StringBuilder out = new StringBuilder();
        out.append("exit code: ").append(result.exitCode())
                .append(" (").append(result.durationMs()).append(" ms)\n");
        // stdout and stderr arrive interleaved, in the order the program wrote them.
        out.append("--- output ---\n").append(result.output());
        if (!result.output().isEmpty() && !result.output().endsWith("\n")) {
            out.append('\n');
        }
        if (result.truncated()) {
            out.append("[output truncated]\n");
        }
        return out.toString();
    }

    /**
     * What the program called, in one line each. The results themselves stay
     * out — keeping them out of the context is why the program ran — but
     * which tools ran, and whether they worked, the model needs to make sense
     * of the output it did get.
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

    private static List<String> languages() {
        return List.of("python", "node");
    }
}
