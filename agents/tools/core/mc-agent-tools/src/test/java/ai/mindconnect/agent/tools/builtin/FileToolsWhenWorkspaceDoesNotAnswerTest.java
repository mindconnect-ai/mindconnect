package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.workspace.WorkspaceEntry;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.workspace.WorkspaceWalker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A workspace that cannot be asked — a remote one answering 401 or timing out —
 * has not said the file is missing. The tools must say they could not check,
 * never "does not exist — use file_write", which had the model overwrite the
 * real file.
 */
class FileToolsWhenWorkspaceDoesNotAnswerTest {

    /** Every call fails the way a remote workspace does when the server refuses. */
    private static final WorkspaceFiles UNREACHABLE = new WorkspaceFiles() {
        @Override
        public FileRoots roots() {
            return FileRoots.of(InMemoryWorkspaceFiles.ROOT);
        }

        @Override
        public Optional<WorkspaceEntry> stat(Path path) throws IOException {
            throw new IOException("HTTP 401 Unauthorized");
        }

        @Override
        public List<WorkspaceEntry> list(Path directory) throws IOException {
            throw new IOException("HTTP 401 Unauthorized");
        }

        @Override
        public void walk(Path start, Set<String> excluded, WorkspaceWalker walker) throws IOException {
            throw new IOException("HTTP 401 Unauthorized");
        }

        @Override
        public byte[] readAllBytes(Path file) throws IOException {
            throw new IOException("HTTP 401 Unauthorized");
        }

        @Override
        public byte[] readHead(Path file, int maxBytes) throws IOException {
            throw new IOException("HTTP 401 Unauthorized");
        }

        @Override
        public void write(Path file, byte[] content) throws IOException {
            throw new AssertionError("nothing may be written when the file could not be checked");
        }

        @Override
        public LocalFile localFile(Path file) throws IOException {
            throw new IOException("HTTP 401 Unauthorized");
        }
    };

    @Test
    void the_conveniences_over_stat_pass_the_failure_on() {
        Path file = InMemoryWorkspaceFiles.ROOT.resolve("notes.md");
        assertThatThrownBy(() -> UNREACHABLE.exists(file)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> UNREACHABLE.isDirectory(file)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> UNREACHABLE.isRegularFile(file)).isInstanceOf(IOException.class);
    }

    @Test
    void edit_says_it_could_not_check_instead_of_sending_the_model_to_file_write() {
        String result = new FileEditTool(UNREACHABLE).execute(Map.of("path", "notes.md",
                "old_string", "a", "new_string", "b"));

        assertThat(result)
                .startsWith("Error: could not check notes.md")
                .contains("HTTP 401 Unauthorized", "do not recreate it")
                .doesNotContain("does not exist", "file_write");
    }

    @Test
    void read_list_grep_and_glob_say_they_could_not_check() {
        assertThat(new FileReadTool(UNREACHABLE).execute(Map.of("path", "notes.md")))
                .startsWith("Error: could not check notes.md").doesNotContain("does not exist");
        assertThat(new FileListTool(UNREACHABLE).execute(Map.of("path", "slides")))
                .startsWith("Error: could not check slides").doesNotContain("does not exist");
        assertThat(new GrepTool(UNREACHABLE).execute(Map.of("pattern", "x", "path", "slides")))
                .startsWith("Error: could not check slides").doesNotContain("does not exist");
        assertThat(new GlobTool(UNREACHABLE).execute(Map.of("pattern", "**", "path", "slides")))
                .startsWith("Error: could not check slides").doesNotContain("does not exist");
    }
}
