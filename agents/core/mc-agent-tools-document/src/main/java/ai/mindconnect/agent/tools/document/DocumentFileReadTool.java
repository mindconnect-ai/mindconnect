package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads any file — plain text, PDF, Word, HTML, etc. — by delegating to
 * {@link DocumentParser} for format detection and extraction.
 * Prefer the lightweight {@code file_read} tool for plain text files.
 */
public class DocumentFileReadTool implements Tool {

    private final Path baseDir;

    public DocumentFileReadTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "document_file_read";
    }

    @Override
    public String description() {
        return "Reads the contents of a file at the given path (relative to the base directory). " +
               "Supports plain text, PDF, Word, HTML, and other document formats via Apache Tika. " +
               "For large multi-page documents prefer document_outline + read_document for targeted access. " +
               "Only for files on the filesystem — a file the user attached to this chat has no path; " +
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
        String relative = (String) arguments.get("path");
        if (relative == null || relative.isBlank()) {
            return "Error: path is required";
        }
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            return "Error: path is outside the allowed base directory";
        }
        if (!Files.exists(target)) {
            return "Error: file does not exist: " + relative;
        }
        if (Files.isDirectory(target)) {
            return "Error: path is a directory, use file_list instead";
        }
        try {
            return DocumentParser.parseFile(target);
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}
