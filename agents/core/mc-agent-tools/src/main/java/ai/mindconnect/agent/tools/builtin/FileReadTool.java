package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class FileReadTool implements Tool {

    private static final int MAX_CHARS = 20_000;

    private final Path baseDir;

    public FileReadTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "Reads the contents of a plain-text file at the given path (relative to the base directory). " +
               "For PDF, Word, or other binary document formats use the document_file_read tool instead. " +
               "Only for files on the filesystem: a file the user attached to this chat has no path — " +
               "use vector_search for it.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Relative path to the file within the base directory"
                        )
                ),
                "required", new String[]{"path"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String raw = ToolPaths.firstString(arguments, ToolPaths.PATH_ALIASES);
        if (raw == null) {
            return ToolPaths.missingArgError("path", ToolPaths.PATH_ALIASES, arguments);
        }
        String relative = ToolPaths.normalise(raw, baseDir);
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            return "Error: path is outside the allowed base directory ("
                    + baseDir + "). Requested: " + raw;
        }
        if (!Files.exists(target)) {
            return "Error: file does not exist: " + relative;
        }
        if (Files.isDirectory(target)) {
            return "Error: path is a directory, use file_list instead";
        }
        try {
            String content = Files.readString(target);
            if (content.length() > MAX_CHARS) {
                return content.substring(0, MAX_CHARS) + "\n\n[truncated after " + MAX_CHARS + " chars]";
            }
            return content;
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}
