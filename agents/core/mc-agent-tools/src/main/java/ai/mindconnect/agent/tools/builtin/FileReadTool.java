package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads a text file the way {@code cat -n} shows it: every line numbered,
 * so a line the model wants to edit or cite has a number to name. A file
 * longer than one call may carry is read in pages — {@code offset} names
 * the first line, {@code limit} how many — and the tool says where to
 * continue. A binary file is refused with a pointer to the document tools.
 */
public class FileReadTool implements Tool {

    /** The most one call returns — a page, not the file. */
    static final int MAX_CHARS = 20_000;
    /** How many lines a call reads when the caller names no limit. */
    static final int DEFAULT_LIMIT = 2_000;
    /** A single line longer than this is cut, with a marker; a minified bundle is not worth the context. */
    static final int MAX_LINE_CHARS = 2_000;

    private final Path baseDir;
    private final FileRoots roots;

    public FileReadTool(Path baseDir) {
        this(FileRoots.of(baseDir));
    }

    /** Rooted at the session's directories — the base for relative paths, the rest by absolute path. */
    public FileReadTool(FileRoots roots) {
        this.roots = roots;
        this.baseDir = roots.base();
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "Reads a text file and returns its lines numbered, `cat -n` style. Reads up to " + DEFAULT_LIMIT
                + " lines (at most " + MAX_CHARS + " characters) per call; for a longer file pass `offset` "
                + "(the first line to read, 1-based) and `limit`, and the result says where to continue. "
                + "Paths are relative to the working directory " + baseDir + " or absolute into one of the "
                + "session's directories. Binary files — PDF, Word, images — are refused: use "
                + "document_file_read or the document tools for those. A file the user attached to this "
                + "chat is on disk when the system prompt names its path; without a path it has none — use "
                + "vector_search for it.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Path of the file, relative to the working directory or absolute"
                        ),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "The first line to read, 1-based. Default 1."
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "How many lines to read from `offset`. Default " + DEFAULT_LIMIT + "."
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
        Path target = roots.resolve(relative).orElse(null);
        if (target == null) {
            return roots.outsideError(raw);
        }
        if (!Files.exists(target)) {
            return "Error: file does not exist: " + relative;
        }
        if (Files.isDirectory(target)) {
            return "Error: path is a directory, use file_list instead";
        }
        int offset = Math.max(1, intArg(arguments.get("offset"), 1));
        int limit = intArg(arguments.get("limit"), DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        if (FileWalks.isBinary(target)) {
            return "Error: " + relative + " is a binary file. For a PDF, Word or other document use "
                    + "document_file_read or read_document; an image goes to the model as an attachment.";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                lines = Files.readAllLines(target, StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                return "Error reading file: " + e2.getMessage();
            }
        }
        return render(relative, lines, offset, limit);
    }

    /** The page: numbered lines from {@code offset}, cut at {@code limit} lines or {@link #MAX_CHARS}, with a pointer onward. */
    static String render(String shownPath, List<String> lines, int offset, int limit) {
        int total = lines.size();
        if (total == 0) {
            return "(empty file)";
        }
        if (offset > total) {
            return "Error: offset " + offset + " is past the end — the file has " + total + " lines.";
        }
        int width = String.valueOf(Math.min(total, offset + limit - 1)).length();
        StringBuilder out = new StringBuilder();
        int line = offset;
        int shown = 0;
        boolean cutByChars = false;
        while (line <= total && shown < limit) {
            String text = lines.get(line - 1);
            if (text.length() > MAX_LINE_CHARS) {
                text = text.substring(0, MAX_LINE_CHARS) + " …[line cut after " + MAX_LINE_CHARS + " chars]";
            }
            String numbered = String.format("%" + width + "d\t%s%n", line, text);
            if (out.length() + numbered.length() > MAX_CHARS && shown > 0) {
                cutByChars = true;
                break;
            }
            out.append(numbered);
            line++;
            shown++;
        }
        int last = offset + shown - 1;
        if (last < total) {
            out.append("\n[lines ").append(offset).append('-').append(last).append(" of ").append(total);
            if (cutByChars) {
                out.append(", cut at ").append(MAX_CHARS).append(" chars");
            }
            out.append(" — continue with offset=").append(last + 1).append(']');
        } else if (offset > 1) {
            out.append("\n[lines ").append(offset).append('-').append(last).append(" of ").append(total)
                    .append(" — end of file]");
        }
        return out.toString().stripTrailing();
    }

    private static int intArg(Object raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
