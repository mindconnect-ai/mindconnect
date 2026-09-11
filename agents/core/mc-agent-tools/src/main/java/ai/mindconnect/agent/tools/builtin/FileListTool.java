package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileListTool implements Tool {

    private final Path baseDir;
    private final FileRoots roots;

    public FileListTool(Path baseDir) {
        this(FileRoots.of(baseDir));
    }

    /** Rooted at the session's directories — the base for relative paths, the rest by absolute path. */
    public FileListTool(FileRoots roots) {
        this.roots = roots;
        this.baseDir = roots.base();
    }

    @Override
    public String name() {
        return "file_list";
    }

    @Override
    public String description() {
        return "Lists files and directories under a given path (relative to the base directory). " +
                "Use '.' or empty string for the base directory itself.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Relative path within the base directory to list"
                        )
                ),
                "required", new String[0]
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String raw = ToolPaths.firstString(arguments, ToolPaths.PATH_ALIASES);
        if (raw == null) raw = ".";
        String relative = ToolPaths.normalise(raw, baseDir);
        if (relative.isBlank()) relative = ".";

        Path target = roots.resolve(relative).orElse(null);
        if (target == null) {
            return roots.outsideError(raw);
        }
        if (!Files.exists(target)) {
            return "Error: path does not exist: " + relative;
        }
        if (!Files.isDirectory(target)) {
            return "Error: path is not a directory: " + relative;
        }

        try (Stream<Path> entries = Files.list(target)) {
            String listing = entries
                    .sorted()
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return Files.isDirectory(p) ? name + "/" : name;
                    })
                    .collect(Collectors.joining("\n"));
            String header = "Directory: " + target.toAbsolutePath();
            return listing.isEmpty() ? header + "\n(empty directory)" : header + "\n" + listing;
        } catch (IOException e) {
            return "Error listing directory: " + e.getMessage();
        }
    }
}
