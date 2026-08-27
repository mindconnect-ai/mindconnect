package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.code.CodeLanguages.CodeLanguage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public CodeExecuteTool(CodeExecutionService service, Map<String, CodeLanguage> languages,
                           String sessionKey, String network) {
        this.service = service;
        this.languages = languages;
        this.sessionKey = sessionKey;
        this.network = network;
    }

    @Override
    public String name() {
        return "code_execute";
    }

    @Override
    public String description() {
        String networkNote = "none".equals(network)
                ? "There is NO network access: no HTTP requests and no pip/npm installs — only the "
                + "packages already in the image are available. "
                : "Network access is enabled: HTTP requests and package installs (pip/npm) work, and "
                + "installed packages persist for this session. ";
        return "Executes a program in an isolated container and returns exit code, stdout and stderr. "
                + "Languages: " + String.join(", ", languages.keySet()) + ". "
                + networkNote
                + "Each call runs a fresh interpreter process, so variables do NOT carry over between calls — "
                + "but the working directory /workspace persists for this session. "
                + "Write files to /workspace to pass data between calls.";
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("language", language, "code", code));
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
        CodeExecutionService.ExecResult result;
        try {
            result = service.execute(sessionKey, language, network, source);
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
}
