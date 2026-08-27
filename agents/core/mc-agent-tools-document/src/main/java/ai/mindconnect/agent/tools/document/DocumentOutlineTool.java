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
 * Returns the structural outline of a long document so the model can decide
 * which sections to read with {@code read_document} or search with
 * {@code grep_document}, without first loading the whole file into the context
 * window.
 * <p>
 * Supports PDF (bookmarks via PDFBox) and Word .docx (heading styles via POI).
 * Plain text files have no outline; for those the tool reports total pages but
 * an empty outline.
 */
public class DocumentOutlineTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DocumentOutlineTool.class);

    private final Path baseDir;
    private final DocumentReader reader;

    public DocumentOutlineTool(Path baseDir, DocumentReader reader) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.reader = reader;
    }

    @Override public String name() { return "document_outline"; }

    @Override
    public String description() {
        return "Returns the structural outline of a long document (PDF or Word .docx) — total " +
               "page count, approximate token count, and a tree of headings/bookmarks with their " +
               "page ranges. Use this FIRST when asked about a long document so you can decide " +
               "which section to read or search.\n" +
               "Path is relative to the base directory.\n" +
               "After this, use `read_document` to fetch a specific page range, or " +
               "`grep_document` to search the full text for a regex.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Path to the document, relative to base directory. Supports .pdf, .docx, plain text."
                        )
                ),
                "required", new String[]{"path"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object rawPath = arguments.get("path");
        if (rawPath == null || rawPath.toString().isBlank()) {
            return "Error: path is required";
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
        long start = System.currentTimeMillis();
        try {
            model = reader.load(baseDir, target);
        } catch (IOException | RuntimeException e) {
            log.warn("document_outline: failed to parse {}: {}", relative, e.getMessage());
            return "Error parsing document: " + e.getMessage();
        }
        long parseMs = System.currentTimeMillis() - start;

        StringBuilder sb = new StringBuilder();
        sb.append("Document: ").append(model.relativePath()).append('\n');
        sb.append("Format: ").append(model.format()).append('\n');
        sb.append("Total pages: ").append(model.totalPages());
        if (model.format() != DocumentModel.Format.PDF) {
            sb.append(" (synthetic — Word/text are split into ~12k-char chunks)");
        }
        sb.append('\n');
        sb.append("Approx tokens: ").append(model.approxTokens()).append('\n');
        sb.append("Parse time: ").append(parseMs).append(" ms\n");
        sb.append("Outline:\n");
        if (model.outline().isEmpty()) {
            sb.append("  (no outline — document has no bookmarks/headings)\n");
            sb.append("  Use grep_document for keyword search or read_document with explicit page ranges.\n");
        } else {
            for (DocumentModel.OutlineEntry e : model.outline()) {
                String indent = "  ".repeat(Math.max(1, e.level()));
                sb.append(indent).append(e.title())
                  .append("  [pages ").append(e.startPage());
                if (e.endPage() > e.startPage()) sb.append("-").append(e.endPage());
                sb.append("]").append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
