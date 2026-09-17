package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.workspace.CommandRunner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public RemoteCodeExecuteTool(CommandRunner runner, Duration timeout) {
        this.runner = runner;
        this.timeout = timeout;
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
                + "for this session when the template allows network access.";
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("language", language, "code", code));
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
        CommandRunner.Result result;
        try {
            result = runner.run(interpreter, source, Map.of(), timeout);
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

    private static List<String> languages() {
        return List.of("python", "node");
    }
}
