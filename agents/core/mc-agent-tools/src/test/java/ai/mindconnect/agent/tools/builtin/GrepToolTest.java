package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** grep: matches with file and line, a name filter, context, files only, and what is skipped. */
class GrepToolTest {

    @TempDir
    Path tmp;

    private Path project() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.createDirectories(project.resolve("src/main"));
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("src/main/App.java"), "class App {\n    void run() {\n        log(\"todo: wire\");\n    }\n}\n");
        Files.writeString(project.resolve("src/main/Util.py"), "# TODO clean up\ndef util():\n    pass\n");
        Files.writeString(project.resolve("README.md"), "# App\n\nSee the todo list.\n");
        Files.writeString(project.resolve("target/App.class.txt"), "todo: never seen\n");
        Files.write(project.resolve("blob.bin"), new byte[]{'t', 'o', 'd', 'o', 0, 1, 2});
        return project;
    }

    @Test
    void matchesComeAsFileLineText_buildDirsAndBinariesSkipped() throws Exception {
        GrepTool grep = new GrepTool(project());

        String out = grep.execute(Map.of("pattern", "todo"));

        assertThat(out).startsWith("Matches: 2 in 2 file(s)\n")
                .contains("src/main/App.java:3:         log(\"todo: wire\");")
                .contains("README.md:3: See the todo list.")
                .doesNotContain("target/").doesNotContain("blob.bin").doesNotContain("TODO clean");
    }

    @Test
    void ignoreCaseGlobContextAndFilesOnly() throws Exception {
        GrepTool grep = new GrepTool(project());

        assertThat(grep.execute(Map.of("pattern", "todo", "ignore_case", true, "glob", "*.py")))
                .isEqualTo("Matches: 1 in 1 file(s)\nsrc/main/Util.py:1: # TODO clean up");
        assertThat(grep.execute(Map.of("pattern", "run\\(\\)", "context", 1)))
                .contains("src/main/App.java-1- class App {\n")
                .contains("src/main/App.java:2:     void run() {\n")
                .contains("src/main/App.java-3-         log(\"todo: wire\");");
        assertThat(grep.execute(Map.of("pattern", "todo", "files_only", "true", "ignore_case", "true")))
                .startsWith("Files with matches: 3\n").contains("src/main/Util.py\n").contains("README.md");
        assertThat(grep.execute(Map.of("pattern", "todo", "path", "src/main/App.java")))
                .as("a single file").isEqualTo("Matches: 1 in 1 file(s)\nsrc/main/App.java:3:         log(\"todo: wire\");");
    }

    @Test
    void limitsErrorsAndRoots() throws Exception {
        Path project = project();
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Files.writeString(lib.resolve("x.txt"), "needle\n");
        Files.createDirectories(tmp.resolve("secret"));
        Files.writeString(tmp.resolve("secret/s.txt"), "needle\n");
        GrepTool grep = new GrepTool(FileRoots.of(project.toString(), List.of(lib.toString())));

        assertThat(grep.execute(Map.of("pattern", "todo", "ignore_case", true, "limit", 1)))
                .startsWith("Matches: 1 in 1 file(s) (stopped at 1 —");
        assertThat(grep.execute(Map.of("pattern", "nothing-here"))).isEqualTo("No matches for 'nothing-here' in .");
        assertThat(grep.execute(Map.of("pattern", "("))).startsWith("Error: invalid regular expression '('");
        assertThat(grep.execute(Map.of("pattern", "x", "path", "nope"))).isEqualTo("Error: path does not exist: nope");
        assertThat(grep.execute(Map.of("pattern", "needle", "path", lib.toString())))
                .as("an additional directory, by absolute path").contains(lib.resolve("x.txt") + ":1: needle");
        assertThat(grep.execute(Map.of("pattern", "needle", "path", tmp.resolve("secret").toString())))
                .startsWith("Error: path is outside the allowed directories");
        assertThat(grep.execute(Map.of())).isEqualTo("Error: pattern is required");
    }
}
