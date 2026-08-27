package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class FileWriteTool implements Tool {

    private final Path baseDir;

    public FileWriteTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public String description() {
        return "Writes content to a file at the given path (relative to the base directory). Creates parent directories if needed.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Relative path to the file within the base directory"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "Content to write to the file"
                        )
                ),
                "required", new String[]{"path", "content"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String raw = ToolPaths.firstString(arguments, ToolPaths.PATH_ALIASES);
        if (raw == null) {
            return ToolPaths.missingArgError("path", ToolPaths.PATH_ALIASES, arguments);
        }
        String content = ToolPaths.firstString(arguments, ToolPaths.CONTENT_ALIASES);
        if (content == null) {
            // Empty file is a legitimate use case — accept "" but not missing.
            Object exact = arguments.get("content");
            if (exact != null) content = exact.toString();
            else return ToolPaths.missingArgError("content", ToolPaths.CONTENT_ALIASES, arguments);
        }

        String relative = ToolPaths.normalise(raw, baseDir);
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            return "Error: path is outside the allowed base directory ("
                    + baseDir + "). Requested: " + raw;
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "Written " + content.length() + " chars to " + target.toAbsolutePath();
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }
}
