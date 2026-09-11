package ai.mindconnect.agent.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as binary, and how text in a legacy encoding is read. */
class FileWalksTest {

    @TempDir
    Path tmp;

    private static final String LATIN_1_LINE = "Größe=Size";

    @Test
    void nulBytesPdfHeadersAndControlCharactersAreBinary_legacyEncodedTextIsNot() {
        assertThat(FileWalks.isBinary(new byte[]{'t', 'o', 'd', 'o', 0, 1, 2})).as("a NUL byte").isTrue();
        assertThat(FileWalks.isBinary(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13}))
                .as("a PNG header").isTrue();
        assertThat(FileWalks.isBinary("%PDF-1.7\n%âã\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1)))
                .as("a PDF").isTrue();
        assertThat(FileWalks.isBinary(new byte[]{1, 2, 3, 4, 5, 'a', 'b', 6, 7, 8})).as("mostly control characters").isTrue();

        assertThat(FileWalks.isBinary((LATIN_1_LINE + "\n").getBytes(StandardCharsets.ISO_8859_1))).as("ISO-8859-1").isFalse();
        assertThat(FileWalks.isBinary(new byte[]{'p', 'r', 'i', 'c', 'e', ' ', (byte) 0x80, ' ', '5', '\r', '\n'}))
                .as("Windows-1252, a euro sign").isFalse();
        assertThat(FileWalks.isBinary((LATIN_1_LINE + "\n").getBytes(StandardCharsets.UTF_8))).as("UTF-8").isFalse();
        assertThat(FileWalks.isBinary("\u001B[32mOK\u001B[0m\tdone\f\n".getBytes(StandardCharsets.UTF_8)))
                .as("a coloured log line").isFalse();
        assertThat(FileWalks.isBinary(new byte[0])).as("an empty file").isFalse();
    }

    @Test
    void fileReadReadsLatin1Text_andStillRefusesABinary() throws Exception {
        Files.write(tmp.resolve("messages_de.properties"), (LATIN_1_LINE + "\n").getBytes(StandardCharsets.ISO_8859_1));
        Files.write(tmp.resolve("blob.bin"), new byte[]{'a', 0, 'b'});
        FileReadTool read = new FileReadTool(tmp);

        assertThat(read.execute(Map.of("path", "messages_de.properties"))).isEqualTo("1\t" + LATIN_1_LINE);
        assertThat(read.execute(Map.of("path", "blob.bin"))).startsWith("Error: blob.bin is a binary file.");
    }

    @Test
    void readTextFallsBackToIso88591_onlyWhenTheBytesAreNoUtf8() throws Exception {
        Path latin = Files.write(tmp.resolve("latin.txt"), LATIN_1_LINE.getBytes(StandardCharsets.ISO_8859_1));
        Path utf8 = Files.write(tmp.resolve("utf8.txt"), LATIN_1_LINE.getBytes(StandardCharsets.UTF_8));

        assertThat(FileWalks.readText(latin)).isEqualTo(new FileWalks.Decoded(LATIN_1_LINE, StandardCharsets.ISO_8859_1));
        assertThat(FileWalks.readText(utf8)).isEqualTo(new FileWalks.Decoded(LATIN_1_LINE, StandardCharsets.UTF_8));
    }
}
