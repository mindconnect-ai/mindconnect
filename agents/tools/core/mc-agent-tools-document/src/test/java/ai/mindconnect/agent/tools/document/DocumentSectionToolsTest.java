package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tools.document.DocumentModel;
import ai.mindconnect.agent.tools.document.DocumentReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic document split: heading-exact sections for Word, markdown
 * headings for text — and the two tools on top ({@code document_sections} as
 * strict JSON, {@code read_document_section} by index or title).
 */
class DocumentSectionToolsTest {

    @TempDir
    Path dir;

    private Path docx;

    @BeforeEach
    void writeDocx() throws Exception {
        docx = dir.resolve("spec.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            paragraph(doc, null, "Preamble text before any heading.");
            paragraph(doc, "Heading1", "Introduction");
            paragraph(doc, null, "Intro line one.");
            paragraph(doc, null, "Intro line two.");
            paragraph(doc, "Heading2", "Details");
            paragraph(doc, null, "The details.");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }
    }

    private static void paragraph(XWPFDocument doc, String style, String text) {
        XWPFParagraph p = doc.createParagraph();
        if (style != null) p.setStyle(style);
        p.createRun().setText(text);
    }

    @Test
    void docxSplitsExactlyAtHeadings() throws Exception {
        DocumentModel model = new DocumentReader().load(dir, docx);

        assertThat(model.sections()).hasSize(3);
        assertThat(model.sections().get(0).title()).isEmpty();
        assertThat(model.sections().get(0).content()).contains("Preamble text");
        assertThat(model.sections().get(1).title()).isEqualTo("Introduction");
        assertThat(model.sections().get(1).level()).isEqualTo(1);
        assertThat(model.sections().get(1).content()).contains("Intro line one.").contains("Intro line two.");
        assertThat(model.sections().get(2).title()).isEqualTo("Details");
        assertThat(model.sections().get(2).content()).isEqualTo("The details.");
    }

    @Test
    void docxWithoutStylesFallsBackToBoldSizeHeuristic() throws Exception {
        // Real-world templates often skip heading styles entirely: headings are
        // just bold paragraphs with a larger font. Body text stays 11pt.
        Path plain = dir.resolve("template.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            styledParagraph(doc, "Title of it all", true, 16);
            styledParagraph(doc, "Some intro body text explaining things.", false, null);
            styledParagraph(doc, "First Chapter", true, 14);
            styledParagraph(doc, "Chapter one content.", false, null);
            styledParagraph(doc, "Second Chapter", true, 14);
            styledParagraph(doc, "Chapter two content, still normal.", false, null);
            try (OutputStream out = Files.newOutputStream(plain)) {
                doc.write(out);
            }
        }

        DocumentModel model = new DocumentReader().load(dir, plain);

        assertThat(model.sections()).extracting(DocumentModel.Section::title)
                .containsExactly("Title of it all", "First Chapter", "Second Chapter");
        assertThat(model.sections().get(0).level()).isEqualTo(1);   // largest size
        assertThat(model.sections().get(1).level()).isEqualTo(2);
        assertThat(model.sections().get(1).content()).isEqualTo("Chapter one content.");
    }

    private static void styledParagraph(XWPFDocument doc, String text, boolean bold, Integer sizePt) {
        XWPFParagraph p = doc.createParagraph();
        var run = p.createRun();
        run.setText(text);
        run.setBold(bold);
        if (sizePt != null) run.setFontSize(sizePt);
    }

    @Test
    void markdownTextSplitsAtHashHeadings() throws Exception {
        Path md = dir.resolve("notes.md");
        Files.writeString(md, "# One\nfirst\n## Two\nsecond\n");

        DocumentModel model = new DocumentReader().load(dir, md);

        assertThat(model.sections()).hasSize(2);
        assertThat(model.sections().get(0).title()).isEqualTo("One");
        assertThat(model.sections().get(1).title()).isEqualTo("Two");
        assertThat(model.sections().get(1).level()).isEqualTo(2);
        assertThat(model.sections().get(1).content()).isEqualTo("second");
    }

    @Test
    void sectionsToolReturnsStrictJson() throws Exception {
        DocumentSectionsTool tool = new DocumentSectionsTool(dir, new DocumentReader());

        String json = tool.execute(Map.of("path", "spec.docx"));
        JsonNode root = new ObjectMapper().readTree(json);

        assertThat(root.get("sectionCount").asInt()).isEqualTo(3);
        assertThat(root.get("sections").get(1).get("title").asText()).isEqualTo("Introduction");
        assertThat(root.get("sections").get(1).get("content").asText()).contains("Intro line one.");
    }

    @Test
    void sectionsToolCanOmitContent() throws Exception {
        DocumentSectionsTool tool = new DocumentSectionsTool(dir, new DocumentReader());

        String json = tool.execute(Map.of("path", "spec.docx", "include_content", false));
        JsonNode root = new ObjectMapper().readTree(json);

        assertThat(root.get("sections").get(1).has("content")).isFalse();
        assertThat(root.get("sections").get(1).get("title").asText()).isEqualTo("Introduction");
    }

    @Test
    void readSectionByIndexAndByTitle() {
        ReadDocumentSectionTool tool = new ReadDocumentSectionTool(dir, new DocumentReader());

        assertThat(tool.execute(Map.of("path", "spec.docx", "section", "3")))
                .startsWith("## Details").contains("The details.");
        assertThat(tool.execute(Map.of("path", "spec.docx", "section", "intro")))
                .startsWith("## Introduction").contains("Intro line one.");
    }

    @Test
    void unknownSectionListsWhatExists() {
        ReadDocumentSectionTool tool = new ReadDocumentSectionTool(dir, new DocumentReader());

        String result = tool.execute(Map.of("path", "spec.docx", "section", "nope"));

        assertThat(result).startsWith("Error: no section matches")
                .contains("2. Introduction")
                .contains("3. Details");
    }
}
