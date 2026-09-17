package ai.mindconnect.agent.tools.builtin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file tools on a workspace that is not this machine's disk: everything
 * they read, list, search and write goes through {@code WorkspaceFiles}.
 */
class FileToolsOnWorkspaceFilesTest {

    private final InMemoryWorkspaceFiles workspace = new InMemoryWorkspaceFiles()
            .put("make_deck.py", "from pptx import Presentation\nprs = Presentation()\n")
            .put("slides/notes.md", "# Notes\nslides: 3\n")
            .put("node_modules/lib/index.js", "Presentation everywhere\n");

    @Test
    void read_list_and_write_use_the_workspace() {
        assertThat(new FileReadTool(workspace).execute(Map.of("path", "/workspace/make_deck.py")))
                .isEqualTo("1\tfrom pptx import Presentation\n2\tprs = Presentation()");
        assertThat(new FileListTool(workspace).execute(Map.of("path", ".")))
                .isEqualTo("Directory: /workspace\nmake_deck.py\nnode_modules/\nslides/");

        assertThat(new FileWriteTool(workspace).execute(Map.of("path", "slides/deck.txt", "content", "hello")))
                .isEqualTo("Written 5 chars to /workspace/slides/deck.txt");
        assertThat(workspace.content("slides/deck.txt")).isEqualTo("hello");
    }

    @Test
    void edit_reads_and_writes_back_through_the_workspace() {
        String result = new FileEditTool(workspace).execute(Map.of("path", "slides/notes.md",
                "old_string", "slides: 3", "new_string", "slides: 4"));

        assertThat(result).startsWith("Edited slides/notes.md").contains("-slides: 3", "+slides: 4");
        assertThat(workspace.content("slides/notes.md")).isEqualTo("# Notes\nslides: 4\n");
    }

    @Test
    void glob_and_grep_walk_the_workspace_and_skip_excluded_directories() {
        assertThat(new GlobTool(workspace).execute(Map.of("pattern", "**")))
                .contains("Matches: 2", "make_deck.py", "slides/notes.md")
                .doesNotContain("node_modules");

        assertThat(new GrepTool(workspace).execute(Map.of("pattern", "Presentation")))
                .isEqualTo("Matches: 2 in 1 file(s)\nmake_deck.py:1: from pptx import Presentation\n"
                        + "make_deck.py:2: prs = Presentation()");
    }

    @Test
    void paths_outside_the_workspace_are_refused() {
        assertThat(new FileReadTool(workspace).execute(Map.of("path", "/etc/passwd")))
                .startsWith("Error: path is outside the allowed directories (/workspace)");
    }
}
