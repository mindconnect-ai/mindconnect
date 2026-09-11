package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file tools rooted at a session's directories: relative paths in the
 * working directory, absolute paths into an additional one, nothing
 * anywhere else.
 */
class FileToolsRootsTest {

    @TempDir
    Path tmp;

    private FileRoots roots() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Files.createDirectories(tmp.resolve("secret"));
        Files.writeString(project.resolve("README.md"), "# project");
        Files.writeString(lib.resolve("util.java"), "class Util {}");
        Files.writeString(tmp.resolve("secret/key.txt"), "s3cr3t");
        return FileRoots.of(project.toString(), List.of(lib.toString()));
    }

    @Test
    void fileReadReachesBothRootsAndNothingElse() throws Exception {
        FileRoots roots = roots();
        FileReadTool read = new FileReadTool(roots);

        assertThat(read.execute(Map.of("path", "README.md"))).isEqualTo("# project");
        assertThat(read.execute(Map.of("path", tmp.resolve("lib/util.java").toString()))).isEqualTo("class Util {}");
        assertThat(read.execute(Map.of("path", tmp.resolve("secret/key.txt").toString())))
                .startsWith("Error: path is outside the allowed directories (");
        assertThat(read.execute(Map.of("path", "../secret/key.txt")))
                .startsWith("Error: path is outside the allowed directories (");
    }

    @Test
    void fileWriteAndListFollowTheSameRule() throws Exception {
        FileRoots roots = roots();
        FileWriteTool write = new FileWriteTool(roots);
        FileListTool list = new FileListTool(roots);

        assertThat(write.execute(Map.of("path", tmp.resolve("lib/new.txt").toString(), "content", "x")))
                .startsWith("Written 1 chars");
        assertThat(write.execute(Map.of("path", tmp.resolve("secret/x.txt").toString(), "content", "x")))
                .startsWith("Error: path is outside");
        assertThat(list.execute(Map.of("path", tmp.resolve("lib").toString()))).contains("util.java").contains("new.txt");
        assertThat(list.execute(Map.of())).contains("README.md");
        assertThat(list.execute(Map.of("path", tmp.resolve("secret").toString()))).startsWith("Error: path is outside");
    }

    @Test
    void globSearchesAnAdditionalDirectoryByAbsolutePath() throws Exception {
        FileRoots roots = roots();
        GlobTool glob = new GlobTool(roots);

        String hits = glob.execute(Map.of("pattern", "*.java", "path", tmp.resolve("lib").toString()));
        assertThat(hits).contains(tmp.resolve("lib/util.java").toString()).doesNotContain("Error");
        assertThat(glob.execute(Map.of("pattern", "*", "path", tmp.resolve("secret").toString())))
                .startsWith("Error: path is outside");
    }

    @Test
    void bashNamesTheAdditionalDirectories() throws Exception {
        BashTool bash = new BashTool(roots());
        assertThat(bash.description())
                .contains("working directory " + tmp.resolve("project"))
                .contains("may also use these directories: " + tmp.resolve("lib"));
    }
}
