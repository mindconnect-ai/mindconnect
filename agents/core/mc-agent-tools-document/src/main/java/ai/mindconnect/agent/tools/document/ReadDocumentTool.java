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

/**
 * Reads a specific page range from a long document, with explicit
 * {@code [page N]} markers so the model can quote sections back to the user
 * verbatim and the user knows where in the document the content came from.
 * <p>
 * Capped at {@link #MAX_CHARS_PER_CALL} so an over-eager model can't pull a
 * whole 1000-page contract into its window in one go.
 */
public class ReadDocumentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadDocumentTool.class);
    /** Hard cap on output length per single tool call (~25k tokens at 4 chars/token). */
    private static final int MAX_CHARS_PER_CALL = 100_000;

    private final Path baseDir;
    private final DocumentReader reader;

    public ReadDocumentTool(Path baseDir, DocumentReader reader) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.reader = reader;
    }

    @Override public String name() { return "read_document"; }

    @Override
    public String description() {
        return "On the filesystem only — a file the user attached to this chat has no path, use vector_search for it. " +
               "Reads a specific page range from a long document (PDF or Word .docx). Returns " +
               "the text with `[page N]` markers so you can cite exact pages back to the user.\n" +
               "Always use `document_outline` FIRST to see how many pages exist and where the " +
               "section you want is. Then call this with `from_page` and `to_page`.\n" +
               "Output is capped at ~25k tokens per call — request a smaller range if needed.";
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
                        "from_page", Map.of(
                                "type", "integer",
                                "description", "First page to include (1-based, inclusive)."
                        ),
                        "to_page", Map.of(
                                "type", "integer",
                                "description", "Last page to include (1-based, inclusive)."
                        )
                ),
                "required", new String[]{"path", "from_page", "to_page"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object rawPath = arguments.get("path");
        if (rawPath == null || rawPath.toString().isBlank()) {
            return "Error: path is required";
        }
        Integer from = parseIntArg(arguments.get("from_page"));
        Integer to = parseIntArg(arguments.get("to_page"));
        if (from == null || to == null) {
            return "Error: from_page and to_page must be integers (1-based)";
        }
        if (from < 1 || to < 1 || to < from) {
            return "Error: invalid page range from_page=" + from + " to_page=" + to;
        }

        String relative = rawPath.toString();
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            return "Error: path is outside the allowed base directory";
        }
        if (!Files.exists(target)) {
            return "Error: file does not exist: " + relative;
        }
        if (!Files.isRegularFile(target)) {
            return "Error: not a regular file: " + relative;
        }

        DocumentModel model;
        try {
            model = reader.load(baseDir, target);
        } catch (IOException | RuntimeException e) {
            log.warn("read_document: failed to parse {}: {}", relative, e.getMessage());
            return "Error parsing document: " + e.getMessage();
        }

        int total = model.totalPages();
        if (from > total) {
            return "Error: from_page=" + from + " is beyond the last page (" + total + ")";
        }
        int effectiveTo = Math.min(to, total);

        StringBuilder sb = new StringBuilder();
        sb.append("Document: ").append(model.relativePath()).append('\n');
        sb.append("Pages ").append(from).append("-").append(effectiveTo)
          .append(" of ").append(total).append('\n');
        sb.append("--\n");

        boolean truncated = false;
        for (int i = from; i <= effectiveTo; i++) {
            String pageText = model.pages().get(i - 1).text();
            String marker = "[page " + i + "]\n";
            if (sb.length() + marker.length() + pageText.length() > MAX_CHARS_PER_CALL) {
                int remaining = Math.max(0, MAX_CHARS_PER_CALL - sb.length() - marker.length());
                sb.append(marker);
                if (remaining > 0) {
                    sb.append(pageText, 0, Math.min(remaining, pageText.length()));
                    if (remaining < pageText.length()) {
                        sb.append("\n[…page ").append(i).append(" truncated mid-page; ");
                    } else {
                        sb.append("\n[");
                    }
                } else {
                    sb.append("\n[page ").append(i).append(" omitted; ");
                }
                sb.append("output cap reached at ~").append(MAX_CHARS_PER_CALL / 4).append(" tokens. ");
                sb.append("Request a smaller page range to see the rest.]");
                truncated = true;
                break;
            }
            sb.append(marker);
            sb.append(pageText);
            if (!pageText.endsWith("\n")) sb.append('\n');
        }
        if (!truncated && effectiveTo < to) {
            sb.append("\n[Note: requested to_page=").append(to)
              .append(" exceeds total pages; stopped at ").append(total).append("]");
        }
        return sb.toString().stripTrailing();
    }

    private static Integer parseIntArg(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
