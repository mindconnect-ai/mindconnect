package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.Tool;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code document_write}: sections in, a Word document out — the closing step
 * of report-generation workflows. Each section becomes a heading (bold, sized
 * by level; when a template document is given, the template's Heading styles
 * are used instead so fonts and numbering carry over) followed by the content
 * split into paragraphs at blank lines.
 */
public final class DocumentWriteTool implements Tool {

    private final Path baseDir;

    public DocumentWriteTool(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String name() {
        return "document_write";
    }

    @Override
    public String description() {
        return "Writes a Word (.docx) document from sections. Each section is an object with "
                + "'title', optional 'level' (1-4, default 1) and 'content'. Pass 'template' "
                + "(path to an existing .docx) to inherit its styles; otherwise headings are "
                + "formatted directly. Overwrites the target file.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> section = Map.of("type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "level", Map.of("type", "integer"),
                        "content", Map.of("type", "string")),
                "required", List.of("title"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "path", Map.of("type", "string", "format", "path",
                        "description", "Target .docx path relative to the base directory."),
                "sections", Map.of("type", "array", "items", section),
                "template", Map.of("type", "string", "format", "path",
                        "description", "Optional .docx whose styles the output inherits.")));
        schema.put("required", List.of("path", "sections"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String relative = arguments.get("path") instanceof String p && !p.isBlank() ? p : null;
        if (relative == null || !(arguments.get("sections") instanceof List<?> sections)
                || sections.isEmpty()) {
            return "Error: 'path' and a non-empty 'sections' array are required.";
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            return "Error: path escapes the base directory.";
        }
        String templateRel = arguments.get("template") instanceof String t && !t.isBlank() ? t : null;
        try {
            XWPFDocument document = openDocument(base, templateRel);
            boolean styled = templateRel != null;
            int written = 0;
            for (Object raw : sections) {
                if (!(raw instanceof Map<?, ?> map)) continue;
                String title = map.get("title") instanceof String s ? s : "";
                int level = clampLevel(map.get("level"));
                String content = map.get("content") instanceof String c ? c : "";
                writeHeading(document, title, level, styled);
                writeContent(document, content);
                written++;
            }
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                document.write(out);
            }
            document.close();
            return "Wrote " + written + " section(s) to " + relative + ".";
        } catch (Exception e) {
            return "Error: document_write failed: " + e.getMessage();
        }
    }

    /** Fresh document, or the template with its body cleared (styles survive). */
    private static XWPFDocument openDocument(Path base, String templateRel) throws Exception {
        if (templateRel == null) {
            return new XWPFDocument();
        }
        Path template = base.resolve(templateRel).normalize();
        if (!template.startsWith(base) || !Files.isRegularFile(template)) {
            throw new IllegalArgumentException("template not found: " + templateRel);
        }
        XWPFDocument document = new XWPFDocument(Files.newInputStream(template));
        for (int i = document.getBodyElements().size() - 1; i >= 0; i--) {
            document.removeBodyElement(i);
        }
        return document;
    }

    private static void writeHeading(XWPFDocument document, String title, int level, boolean styled) {
        XWPFParagraph paragraph = document.createParagraph();
        if (styled) {
            paragraph.setStyle("Heading" + level);
            paragraph.createRun().setText(title);
        } else {
            // No style source: direct formatting the reader's heading
            // heuristic (bold + larger size) round-trips.
            XWPFRun run = paragraph.createRun();
            run.setText(title);
            run.setBold(true);
            run.setFontSize(switch (level) { case 1 -> 16; case 2 -> 14; case 3 -> 13; default -> 12; });
        }
    }

    private static void writeContent(XWPFDocument document, String content) {
        for (String block : content.split("\n\n+")) {
            String text = block.strip();
            if (text.isEmpty()) continue;
            XWPFParagraph paragraph = document.createParagraph();
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) paragraph.createRun().addBreak();
                paragraph.createRun().setText(lines[i]);
            }
        }
    }

    private static int clampLevel(Object raw) {
        try {
            int level = raw == null ? 1 : Integer.parseInt(String.valueOf(raw));
            return Math.max(1, Math.min(level, 4));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
