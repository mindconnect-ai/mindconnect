package ai.mindconnect.agent.runtime.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A session's directories as a file explorer sees them: listed, opened, and never left. */
class SessionDirectoriesTest {

    @TempDir
    Path tmp;

    Path work;
    Path outside;
    SessionDirectories dirs;
    String root;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectories(tmp.resolve("work")).toRealPath();
        outside = Files.createDirectories(tmp.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("secret.txt"), "not yours");
        Files.createDirectories(work.resolve("out/charts"));
        Files.writeString(work.resolve("report.md"), "# Report");
        Files.writeString(work.resolve("out/data.csv"), "a,b\n1,2\n");
        dirs = new SessionDirectories(List.of(work));
        root = work.toString();
    }

    @Test
    void aRootListsFoldersFirstThenFiles_withPathsRelativeToIt() {
        var listing = dirs.list(root, "").orElseThrow();

        assertThat(listing.path()).isEmpty();
        assertThat(listing.entries()).extracting(SessionDirectories.Entry::name)
                .containsExactly("out", "report.md");
        var report = listing.entries().get(1);
        assertThat(report.directory()).isFalse();
        assertThat(report.size()).isEqualTo("# Report".length());
        assertThat(report.path()).isEqualTo("report.md");
        assertThat(listing.truncated()).isFalse();
    }

    @Test
    void aSubfolderListsWithItsOwnPath_andItsFilesOpen() {
        var listing = dirs.list(root, "out").orElseThrow();

        assertThat(listing.path()).isEqualTo("out");
        assertThat(listing.entries()).extracting(SessionDirectories.Entry::path)
                .containsExactly("out/charts", "out/data.csv");
        assertThat(dirs.file(root, "out/data.csv")).contains(work.resolve("out/data.csv"));
        assertThat(dirs.file(root, "out")).as("a folder is no file").isEmpty();
        assertThat(dirs.list(root, "report.md")).as("a file is no folder").isEmpty();
    }

    @Test
    void nothingOutsideTheRootIsReachable() {
        assertThat(dirs.file(root, "../outside/secret.txt")).isEmpty();
        assertThat(dirs.file(root, "out/../../outside/secret.txt")).isEmpty();
        assertThat(dirs.list(root, "..")).isEmpty();
        assertThat(dirs.file(root, outside.resolve("secret.txt").toString()))
                .as("an absolute path is taken as relative to the root").isEmpty();
    }

    @Test
    void onlyTheSessionsRootsAreRoots() {
        assertThat(dirs.list(outside.toString(), "")).isEmpty();
        assertThat(dirs.file(outside.toString(), "secret.txt")).isEmpty();
        assertThat(dirs.list(null, "")).isEmpty();
    }

    @Test
    void aLinkOutOfTheRootIsNeitherListedNorOpened() throws Exception {
        Files.createSymbolicLink(work.resolve("escape.txt"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(work.resolve("escape-dir"), outside);
        Files.createSymbolicLink(work.resolve("inside.md"), work.resolve("report.md"));

        assertThat(dirs.list(root, "").orElseThrow().entries()).extracting(SessionDirectories.Entry::name)
                .containsExactly("out", "inside.md", "report.md");
        assertThat(dirs.file(root, "escape.txt")).isEmpty();
        assertThat(dirs.list(root, "escape-dir")).isEmpty();
        assertThat(dirs.file(root, "escape-dir/secret.txt")).isEmpty();
        assertThat(dirs.file(root, "inside.md")).as("a link that stays inside opens")
                .contains(work.resolve("report.md"));
    }

    @Test
    void missingRootsAreLeftOut_andDuplicatesFolded() {
        var withGaps = new SessionDirectories(List.of(work, tmp.resolve("gone"), work, outside));

        assertThat(withGaps.roots()).containsExactly(work, outside);
    }

    @Test
    void aVeryLargeFolderIsCut() throws Exception {
        Path big = Files.createDirectories(work.resolve("big"));
        for (int i = 0; i <= SessionDirectories.MAX_ENTRIES; i++) {
            Files.writeString(big.resolve("f" + i + ".txt"), "");
        }

        var listing = dirs.list(root, "big").orElseThrow();

        assertThat(listing.entries()).hasSize(SessionDirectories.MAX_ENTRIES);
        assertThat(listing.truncated()).isTrue();
    }

    @Test
    void a_workspace_elsewhere_is_listed_first_without_its_internals_and_opens_through_its_port() throws Exception {
        Path remote = Files.createDirectories(work.resolve("remote"));
        Files.createDirectories(remote.resolve(".home"));
        Files.createDirectories(remote.resolve("deck"));
        Files.writeString(remote.resolve("deck/sales.pptx"), "pptx-bytes");
        var workspace = ai.mindconnect.agent.tool.workspace.WorkspaceFiles.local(
                ai.mindconnect.agent.tool.FileRoots.of(remote));
        SessionDirectories dirs = new SessionDirectories(List.of(work), java.util.Map.of("/workspace", workspace));

        assertThat(dirs.roots()).containsExactly(Path.of("/workspace"), work);
        assertThat(dirs.list("/workspace", "").orElseThrow().entries())
                .extracting(SessionDirectories.Entry::name).containsExactly("deck");
        assertThat(dirs.list("/workspace", "deck").orElseThrow().entries())
                .extracting(SessionDirectories.Entry::path).containsExactly("deck/sales.pptx");
        var content = dirs.open("/workspace", "deck/sales.pptx").orElseThrow();
        assertThat(content.size()).isEqualTo(10);
        assertThat(new String(content.stream().readAllBytes())).isEqualTo("pptx-bytes");
        assertThat(dirs.open("/workspace", "../../etc/passwd")).isEmpty();
    }
}
