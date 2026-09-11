package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One base for relative paths, more roots for absolute ones — and nothing
 * outside any of them.
 */
class FileRootsTest {

    @TempDir
    Path tmp;

    @Test
    void relativePathsResolveAgainstTheBase_andStayInside() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        FileRoots roots = FileRoots.of(project);

        assertThat(roots.resolve("src/App.java")).contains(project.resolve("src/App.java"));
        assertThat(roots.resolve(".")).contains(project);
        assertThat(roots.resolve("")).contains(project);
        assertThat(roots.resolve("../secret")).isEmpty();
        assertThat(roots.resolve(project + "/../secret")).isEmpty();
        assertThat(roots.resolve(null)).isEmpty();
    }

    @Test
    void additionalDirectoriesAreReachableByAbsolutePath() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        FileRoots roots = FileRoots.of(project.toString(), java.util.Arrays.asList(lib.toString(), " ", null));

        assertThat(roots.extra()).containsExactly(lib);
        assertThat(roots.resolve(lib + "/util.java")).contains(lib.resolve("util.java"));
        assertThat(roots.resolve(lib + "/../other/x")).as("dot-dot out of an extra root").isEmpty();
        assertThat(roots.resolve(other.toString())).isEmpty();
        assertThat(roots.resolve("../lib/util.java"))
                .as("a relative path may land in an additional root — it is where it ends up that counts")
                .contains(lib.resolve("util.java"));
        assertThat(roots.resolve("../other/x")).isEmpty();
        assertThat(roots.contains(lib.resolve("deep/file"))).isTrue();
    }

    @Test
    void displayAndDescribeSpeakInTheUsersTerms() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        FileRoots roots = FileRoots.of(project.toString(), List.of(lib.toString(), project.toString()));

        assertThat(roots.extra()).as("the base is not listed twice").containsExactly(lib);
        assertThat(roots.display(project.resolve("src/App.java"))).isEqualTo("src/App.java");
        assertThat(roots.display(project)).isEqualTo(".");
        assertThat(roots.display(lib.resolve("x"))).isEqualTo(lib.resolve("x").toString());
        assertThat(roots.describe()).isEqualTo(project + " (also " + lib + ")");
        assertThat(FileRoots.of(project).describe()).isEqualTo(project.toString());
        assertThat(roots.outsideError("/etc/passwd"))
                .startsWith("Error: path is outside the allowed directories (")
                .endsWith("Requested: /etc/passwd");
    }

    @Test
    void tildeStandsForHome() {
        String home = System.getProperty("user.home");
        FileRoots roots = FileRoots.of(Path.of(home));
        assertThat(roots.resolve("~/notes.md")).contains(Path.of(home, "notes.md"));
        assertThat(roots.resolve("$HOME/notes.md")).contains(Path.of(home, "notes.md"));
        assertThat(roots.resolve("~")).contains(Path.of(home));
    }
}
