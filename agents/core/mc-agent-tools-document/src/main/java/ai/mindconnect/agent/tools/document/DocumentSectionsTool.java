package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.document.DocumentModel;
import ai.mindconnect.agent.tools.document.DocumentReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Returns a document's structure as data: one entry per heading with its exact
 * content — the deterministic counterpart to asking an LLM to split a document.
 * Output is strict JSON, so workflows can {@code JSON.parse} it directly.
 */
public final class DocumentSectionsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DocumentSectionsTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path baseDir;
    private final DocumentReader reader;

    public DocumentSectionsTool(Path baseDir, DocumentReader reader) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.reader = reader;
    }

    @Override
    public String name() {
        return "document_sections";
    }

    @Override
    public String description() {
        return "Splits a document (PDF, Word .docx, text/markdown) into its sections — one per " +
               "heading, each with its exact content — and returns them as a JSON array: " +
               "{\"path\", \"sectionCount\", \"sections\": [{\"index\", \"level\", \"title\", \"content\"}]}. " +
               "Word boundaries are exact (heading styles); PDFs are page-granular (bookmarks). " +
               "With include_content=false only the structure is returned (like document_outline, " +
               "but as data). Note: with content included the result can be large — for a quick " +
               "overview prefer include_content=false or document_outline.";
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
                        "include_content", Map.of(
                                "type", "boolean",
                                "description", "Include each section's content (default true); false returns only index/level/title."
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
        boolean includeContent = !(arguments.get("include_content") instanceof Boolean b) || b;

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
            ObjectNode root = MAPPER.createObjectNode();
            root.put("path", model.relativePath());
            root.put("sectionCount", model.sections().size());
            ArrayNode sections = root.putArray("sections");
            for (DocumentModel.Section section : model.sections()) {
                ObjectNode node = sections.addObject();
                node.put("index", section.index());
                node.put("level", section.level());
                node.put("title", section.title());
                if (includeContent) {
                    node.put("content", section.content());
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("document_sections: failed to parse {}: {}", relative, e.getMessage());
            return "Error: could not parse document: " + e.getMessage();
        }
    }
}
