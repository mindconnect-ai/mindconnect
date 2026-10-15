package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.code.CodeLanguages.CodeLanguage;

import java.nio.file.Path;
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
    /** Host directory visible inside the container, or {@code null} for none. */
    private final HostMount mount;
    /** The chat's directories, mounted under their host paths; the working directory also at {@code /workspace}. */
    private final SessionDirs dirs;

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
        this.mount = mount;
        this.dirs = dirs == null ? SessionDirs.none() : dirs;
        this.service = service;
        this.languages = languages;
        this.sessionKey = sessionKey;
        this.network = network;
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
            result = service.execute(sessionKey, language, network, mount, dirs, source);
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
