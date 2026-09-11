package ai.mindconnect.agent.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A glob rooted at a project searches the project: {@code path} is
 * optional there. Rooted at the user's home — the runtime default — it
 * still refuses the root, because that walk covers everything.
 */
class GlobToolWorkingDirTest {

    @TempDir
    Path project;

    @Test
    void inAProjectTheRootIsSearchable_andPathIsOptional() throws Exception {
        Files.createDirectories(project.resolve("src/main"));
        Files.writeString(project.resolve("src/main/App.java"), "class App {}");
        Files.writeString(project.resolve("README.md"), "# app");
        GlobTool glob = new GlobTool(project);

        String dot = glob.execute(Map.of("pattern", "**/*.java", "path", "."));
        assertThat(dot).contains("src/main/App.java").doesNotContain("Error");

        String noPath = glob.execute(Map.of("pattern", "*.md"));
        assertThat(noPath).contains("README.md").doesNotContain("Error");

        assertThat(glob.description()).contains("defaults to the working directory itself");
        assertThat((String[]) glob.parametersSchema().get("required")).containsExactly("pattern");
    }

    @Test
    void atHomeTheRootIsStillRefused() {
        GlobTool glob = new GlobTool(Path.of(System.getProperty("user.home")));

        assertThat(glob.execute(Map.of("pattern", "*.md", "path", "."))).startsWith("Error: path is required");
        assertThat(glob.execute(Map.of("pattern", "*.md"))).startsWith("Error: path is required");
        assertThat((String[]) glob.parametersSchema().get("required")).containsExactly("pattern", "path");
    }
}
