package ai.mindconnect.agent.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** file_read: numbered lines, pages through a long file, refuses a binary one. */
class FileReadToolPagingTest {

    @TempDir
    Path tmp;

    @Test
    void linesAreNumberedLikeCatN() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "first\nsecond\n\nfourth\n");
        FileReadTool read = new FileReadTool(tmp);

        assertThat(read.execute(Map.of("path", "a.txt")))
                .isEqualTo("1\tfirst\n2\tsecond\n3\t\n4\tfourth");
        Files.writeString(tmp.resolve("empty.txt"), "");
        assertThat(read.execute(Map.of("path", "empty.txt"))).isEqualTo("(empty file)");
    }

    @Test
    void offsetAndLimitPageThroughAFile_andTheResultSaysWhereToContinue() throws Exception {
        String content = IntStream.rangeClosed(1, 30).mapToObj(i -> "line " + i)
                .collect(Collectors.joining("\n")) + "\n";
        Files.writeString(tmp.resolve("long.txt"), content);
        FileReadTool read = new FileReadTool(tmp);

        String page = read.execute(Map.of("path", "long.txt", "offset", 11, "limit", 10));
        assertThat(page).startsWith("11\tline 11\n").contains("\n20\tline 20\n")
                .doesNotContain("line 21").doesNotContain("line 10")
                .endsWith("[lines 11-20 of 30 — continue with offset=21]");

        String last = read.execute(Map.of("path", "long.txt", "offset", "21"));
        assertThat(last).contains("30\tline 30").endsWith("[lines 21-30 of 30 — end of file]");

        assertThat(read.execute(Map.of("path", "long.txt", "offset", 31)))
                .isEqualTo("Error: offset 31 is past the end — the file has 30 lines.");
        assertThat(read.execute(Map.of("path", "long.txt"))).as("the whole short file: no footer")
                .endsWith("30\tline 30");
    }

    @Test
    void aPageIsCutAtTheCharacterCap_andALongLineIsCut() throws Exception {
        String big = IntStream.rangeClosed(1, 5_000).mapToObj(i -> "x".repeat(50) + " " + i)
                .collect(Collectors.joining("\n"));
        Files.writeString(tmp.resolve("big.txt"), big);
        FileReadTool read = new FileReadTool(tmp);

        String page = read.execute(Map.of("path", "big.txt"));
        assertThat(page.length()).isLessThan(FileReadTool.MAX_CHARS + 200);
        assertThat(page).contains("of 5000, cut at " + FileReadTool.MAX_CHARS + " chars — continue with offset=");

        Files.writeString(tmp.resolve("wide.txt"), "y".repeat(5_000) + "\nshort\n");
        assertThat(read.execute(Map.of("path", "wide.txt")))
                .contains("…[line cut after " + FileReadTool.MAX_LINE_CHARS + " chars]")
                .contains("\n2\tshort");
    }

    @Test
    void aBinaryFileIsRefused() throws Exception {
        Files.write(tmp.resolve("blob.bin"), new byte[]{'P', 'K', 3, 4, 0, 0, 1, 2});
        FileReadTool read = new FileReadTool(tmp);

        assertThat(read.execute(Map.of("path", "blob.bin")))
                .startsWith("Error: blob.bin is a binary file.").contains("document_file_read");
    }
}
