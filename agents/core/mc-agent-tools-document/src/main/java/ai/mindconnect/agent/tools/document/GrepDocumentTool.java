package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.document.DocumentModel;
import ai.mindconnect.agent.tools.document.DocumentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Full-text regex search inside a single long document. Returns each match with
 * its page number and a few lines of surrounding context, so the model can
 * locate specific clauses ("termination", "liability") without reading the
 * whole document.
 * <p>
 * Output capped at {@link #MAX_MATCHES} hits and {@link #MAX_CHARS} characters
 * total — large documents can produce thousands of matches and we want bounded
 * output.
 */
public class GrepDocumentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepDocumentTool.class);
    private static final int DEFAULT_CONTEXT_LINES = 2;
    private static final int MAX_CONTEXT_LINES = 10;
    private static final int MAX_MATCHES = 50;
    private static final int MAX_CHARS = 30_000;

    private final Path baseDir;
    private final DocumentReader reader;

    public GrepDocumentTool(Path baseDir, DocumentReader reader) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.reader = reader;
    }

    @Override public String name() { return "grep_document"; }

    @Override
    public String description() {
        return "Searches inside a single long document (PDF or Word .docx) for a regex pattern. " +
               "Returns each match with `[page N]` and the surrounding lines, so you can locate " +
               "specific clauses or terms without reading the whole file.\n" +
               "Use this when the user asks about a specific term (e.g. 'liability clause', " +
               "'termination', a specific date or amount). For exploring the document's structure, " +
               "use `document_outline`. To read a contiguous page range, use `read_document`.\n" +
               "Pattern is a Java regex. Matching is case-insensitive by default.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Path to the document, relative to base directory."
                        ),
                        "pattern", Map.of(
                                "type", "string",
                                "description", "Regex pattern (Java syntax). Case-insensitive by default."
                        ),
                        "context_lines", Map.of(
                                "type", "integer",
                                "description", "Lines of context above and below each match. Default 2, max 10."
                        ),
                        "case_sensitive", Map.of(
                                "type", "boolean",
                                "description", "Set true to make matching case-sensitive. Default false."
                        )
                ),
                "required", new String[]{"path", "pattern"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object rawPath = arguments.get("path");
        Object rawPattern = arguments.get("pattern");
        if (rawPath == null || rawPath.toString().isBlank()) return "Error: path is required";
        if (rawPattern == null || rawPattern.toString().isBlank()) return "Error: pattern is required";

        String relative = rawPath.toString();
        String patternStr = rawPattern.toString();
        int contextLines = parseContextLines(arguments.get("context_lines"));
        boolean caseSensitive = parseBool(arguments.get("case_sensitive"), false);

        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) return "Error: path is outside the allowed base directory";
        if (!Files.exists(target)) return "Error: file does not exist: " + relative;
        if (!Files.isRegularFile(target)) return "Error: not a regular file: " + relative;

        Pattern compiled;
        try {
            compiled = Pattern.compile(patternStr, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex '" + patternStr + "': " + e.getMessage();
        }

        DocumentModel model;
        try {
            model = reader.load(baseDir, target);
        } catch (IOException | RuntimeException e) {
            log.warn("grep_document: failed to parse {}: {}", relative, e.getMessage());
            return "Error parsing document: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Document: ").append(model.relativePath()).append('\n');
        sb.append("Pattern: ").append(patternStr);
        if (!caseSensitive) sb.append("  (case-insensitive)");
        sb.append('\n');
        sb.append("--\n");

        int totalMatches = 0;
        boolean capped = false;
        outer:
        for (DocumentModel.Page page : model.pages()) {
            String text = page.text();
            if (text.isEmpty()) continue;
            String[] lines = text.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!compiled.matcher(lines[i]).find()) continue;
                totalMatches++;
                if (totalMatches > MAX_MATCHES) {
                    capped = true;
                    break outer;
                }
                int from = Math.max(0, i - contextLines);
                int to = Math.min(lines.length - 1, i + contextLines);
                sb.append("[page ").append(page.number()).append(", line ").append(i + 1).append("]\n");
                for (int k = from; k <= to; k++) {
                    sb.append(k == i ? "▸ " : "  ");
                    sb.append(lines[k]).append('\n');
                }
                sb.append('\n');
                if (sb.length() > MAX_CHARS) {
                    capped = true;
                    break outer;
                }
            }
        }
        if (totalMatches == 0) {
            sb.append("No matches.\n");
            sb.append("Tip: try a shorter or more lenient pattern, or use document_outline to ");
            sb.append("see the document's structure.");
            return sb.toString().stripTrailing();
        }
        sb.append("--\n");
        if (capped) {
            sb.append("Showing first ").append(Math.min(totalMatches, MAX_MATCHES))
              .append(" matches (output capped). Refine the pattern to narrow down.");
        } else {
            sb.append("Total matches: ").append(totalMatches);
        }
        return sb.toString().stripTrailing();
    }

    private static int parseContextLines(Object raw) {
        if (raw == null) return DEFAULT_CONTEXT_LINES;
        try {
            int v = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString());
            if (v < 0) return 0;
            return Math.min(v, MAX_CONTEXT_LINES);
        } catch (NumberFormatException e) {
            return DEFAULT_CONTEXT_LINES;
        }
    }

    private static boolean parseBool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean b) return b;
        return Boolean.parseBoolean(raw.toString());
    }
}
