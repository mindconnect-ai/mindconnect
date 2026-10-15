package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tools.document.DocumentModel;
import ai.mindconnect.agent.tools.document.DocumentReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * document_write round-trips through our own reader: the written headings
 * (bold + size) are recognised as sections again, contents preserved.
 */
class DocumentWriteToolTest {

    @TempDir
    Path dir;

    @Test
    void writesSectionsThatTheReaderRecognises() throws Exception {
        var tool = new DocumentWriteTool(dir);

        String result = tool.execute(Map.of(
                "path", "out/report.docx",
                "sections", List.of(
                        Map.of("title", "Introduction", "level", 1,
                                "content", "First paragraph.\n\nSecond paragraph."),
                        Map.of("title", "Findings", "level", 2,
                                "content", "The findings body."))));

        assertThat(result).startsWith("Wrote 2 section(s)");

        DocumentModel model = new DocumentReader().load(dir, dir.resolve("out/report.docx"));
        assertThat(model.sections()).extracting(DocumentModel.Section::title)
                .containsExactly("Introduction", "Findings");
        assertThat(model.sections().get(0).content())
                .contains("First paragraph.").contains("Second paragraph.");
        assertThat(model.sections().get(1).content()).isEqualTo("The findings body.");
    }

    @Test
    void validatesInput() {
        var tool = new DocumentWriteTool(dir);
        assertThat(tool.execute(Map.of("path", "x.docx", "sections", List.of())))
                .startsWith("Error:");
        assertThat(tool.execute(Map.of("path", "../escape.docx",
                "sections", List.of(Map.of("title", "t")))))
                .startsWith("Error:");
    }
}
