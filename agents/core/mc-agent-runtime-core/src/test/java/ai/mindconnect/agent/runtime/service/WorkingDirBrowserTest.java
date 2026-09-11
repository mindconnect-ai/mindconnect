package ai.mindconnect.agent.runtime.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a picker sees: the policy's tree, one level at a time, directories only. */
class WorkingDirBrowserTest {

    /** Real path: on macOS the temp directory is a symlink, and the browser answers real paths. */
    Path tmp;

    @BeforeEach
    void realTmp(@TempDir Path dir) throws Exception {
        tmp = dir.toRealPath();
    }

    @Test
    void startsAtTheRootAndListsDirectoriesOnly() throws Exception {
        Files.createDirectories(tmp.resolve("root/beta"));
        Files.createDirectories(tmp.resolve("root/Alpha/src"));
        Files.createDirectories(tmp.resolve("root/.git"));
        Files.writeString(tmp.resolve("root/notes.txt"), "x");
        var browser = new WorkingDirBrowser(WorkingDirPolicy.within(tmp.resolve("root").toString()));

        var top = browser.list("alice", null);

        assertThat(top.root()).isEqualTo(tmp.resolve("root").toString());
        assertThat(top.current()).isEqualTo(tmp.resolve("root").toString());
        assertThat(top.parent()).as("the root has no way up").isNull();
        assertThat(top.subdirs()).containsExactly(
                tmp.resolve("root/Alpha").toString(), tmp.resolve("root/beta").toString());
        assertThat(top.truncated()).isFalse();

        var alpha = browser.list("alice", tmp.resolve("root/Alpha").toString());
        assertThat(alpha.parent()).isEqualTo(tmp.resolve("root").toString());
        assertThat(alpha.subdirs()).containsExactly(tmp.resolve("root/Alpha/src").toString());
    }

    @Test
    void neverLeavesTheRoot() throws Exception {
        Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(tmp.resolve("elsewhere"));
        var browser = new WorkingDirBrowser(WorkingDirPolicy.within(tmp.resolve("root").toString()));

        assertThatThrownBy(() -> browser.list("alice", tmp.resolve("elsewhere").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
        assertThatThrownBy(() -> browser.list("alice", tmp.resolve("root/../elsewhere").toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> browser.list("alice", tmp.resolve("root/nope").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a directory");
    }

    @Test
    void aPerUserRootShowsTheUsersOwnTree() throws Exception {
        var browser = new WorkingDirBrowser(WorkingDirPolicy.within(tmp.resolve("users/{user}").toString()));
        Files.createDirectories(tmp.resolve("users/bob/secret"));

        var alice = browser.list("alice", null);

        assertThat(alice.root()).isEqualTo(tmp.resolve("users/alice").toString());
        assertThat(alice.subdirs()).isEmpty();
        assertThatThrownBy(() -> browser.list("alice", tmp.resolve("users/bob").toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unrestrictedStartsAtHome_andABigDirectoryIsCut() throws Exception {
        var unrestricted = new WorkingDirBrowser(WorkingDirPolicy.unrestricted());
        assertThat(unrestricted.list("u", null).current()).isEqualTo(System.getProperty("user.home"));
        assertThat(unrestricted.list("u", null).root()).isNull();

        Path big = Files.createDirectories(tmp.resolve("big"));
        for (int i = 0; i < WorkingDirBrowser.MAX_ENTRIES + 5; i++) {
            Files.createDirectories(big.resolve(String.format("d%04d", i)));
        }
        var listing = unrestricted.list("u", big.toString());
        assertThat(listing.subdirs()).hasSize(WorkingDirBrowser.MAX_ENTRIES);
        assertThat(listing.truncated()).isTrue();
    }

    @Test
    void aFolderIsCreatedInsideTheTree_andNowhereElse() throws Exception {
        Files.createDirectories(tmp.resolve("root/projects"));
        var browser = new WorkingDirBrowser(WorkingDirPolicy.within(tmp.resolve("root").toString()));

        Path created = browser.create("alice", tmp.resolve("root/projects").toString(), " my-app ");
        assertThat(created).isEqualTo(tmp.resolve("root/projects/my-app"));
        assertThat(Files.isDirectory(created)).isTrue();
        assertThat(browser.create("alice", null, "at-root")).as("no parent: the root")
                .isEqualTo(tmp.resolve("root/at-root"));
        assertThat(browser.create("alice", tmp.resolve("root/projects").toString(), "my-app"))
                .as("creating what exists is fine").isEqualTo(created);

        assertThatThrownBy(() -> browser.create("alice", tmp.toString(), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
        for (String bad : java.util.List.of("", "  ", "a/b", "..", ".", ".hidden")) {
            assertThatThrownBy(() -> browser.create("alice", tmp.resolve("root").toString(), bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        Files.writeString(tmp.resolve("root/notes.txt"), "x");
        assertThatThrownBy(() -> browser.create("alice", tmp.resolve("root").toString(), "notes.txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A file of that name");
    }
}
