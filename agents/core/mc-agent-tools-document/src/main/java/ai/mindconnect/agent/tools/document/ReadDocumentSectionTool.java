package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.document.DocumentModel;
import ai.mindconnect.agent.tools.document.DocumentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads exactly one section of a document, addressed by its 1-based index or
 * by a case-insensitive title match — the surgical companion to
 * {@code document_sections}: list the structure first, then fetch just the
 * section you need.
 */
public final class ReadDocumentSectionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadDocumentSectionTool.class);

    private final Path baseDir;
    private final DocumentReader reader;

    public ReadDocumentSectionTool(Path baseDir, DocumentReader reader) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.reader = reader;
    }

    @Override
    public String name() {
        return "read_document_section";
    }

    @Override
    public String description() {
        return "Reads one section of a document (PDF, Word .docx, text/markdown), addressed by its " +
               "1-based index or by (part of) its heading title. Use document_sections with " +
               "include_content=false or document_outline first to see what sections exist.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Path to the document, relative to the base directory."
                        ),
                        "section", Map.of(
                                "type", "string",
                                "description", "The section's 1-based index (e.g. \"3\") or a case-insensitive part of its title."
                        )
                ),
                "required", new String[]{"path", "section"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object rawPath = arguments.get("path");
        if (rawPath == null || rawPath.toString().isBlank()) {
            return "Error: path is required";
        }
        Object rawSection = arguments.get("section");
        if (rawSection == null || rawSection.toString().isBlank()) {
            return "Error: section is required (a 1-based index or part of the heading title)";
        }

        String relative = rawPath.toString();
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            return "Error: path is outside the allowed base directory";
        }
        if (!Files.exists(target)) {
            return "Error: file does not exist: " + relative;
        }

        try {
            DocumentModel model = reader.load(baseDir, target);
            DocumentModel.Section section = find(model, rawSection.toString().trim());
            if (section == null) {
                StringBuilder sb = new StringBuilder("Error: no section matches '")
                        .append(rawSection).append("'. Available sections:\n");
                for (DocumentModel.Section s : model.sections()) {
                    sb.append("  ").append(s.index()).append(". ")
                      .append(s.title().isBlank() ? "(untitled)" : s.title()).append('\n');
                }
                return sb.toString();
            }
            String heading = section.title().isBlank()
                    ? "(untitled section " + section.index() + ")"
                    : section.title();
            return "## " + heading + "\n\n" + section.content();
        } catch (Exception e) {
            log.warn("read_document_section: failed to parse {}: {}", relative, e.getMessage());
            return "Error: could not parse document: " + e.getMessage();
        }
    }

    private static DocumentModel.Section find(DocumentModel model, String selector) {
        try {
            int index = Integer.parseInt(selector);
            for (DocumentModel.Section s : model.sections()) {
                if (s.index() == index) return s;
            }
            return null;
        } catch (NumberFormatException ignored) {
            // not a number — match by title
        }
        String needle = selector.toLowerCase();
        for (DocumentModel.Section s : model.sections()) {
            if (s.title().toLowerCase().contains(needle)) return s;
        }
        return null;
    }
}
