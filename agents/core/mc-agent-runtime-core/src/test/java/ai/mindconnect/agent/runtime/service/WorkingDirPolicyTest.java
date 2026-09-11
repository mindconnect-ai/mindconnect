package ai.mindconnect.agent.runtime.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a session may take as its working directory: an existing directory,
 * normalised to its real absolute path, and — with a root — one under it.
 */
class WorkingDirPolicyTest {

    @TempDir
    Path tmp;

    @Test
    void anExistingDirectoryComesBackAbsoluteAndNormalised() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("src/app"));

        String dir = WorkingDirPolicy.unrestricted().validate(tmp + "/src/./app/../app");

        assertThat(dir).isEqualTo(project.toRealPath().toString());
    }

    @Test
    void nothingStaysNothing() {
        var policy = WorkingDirPolicy.unrestricted();
        assertThat(policy.validate(null)).isNull();
        assertThat(policy.validate("  ")).isNull();
    }

    @Test
    void aMissingDirectoryAndAFileAreRefused() throws Exception {
        Path file = Files.writeString(tmp.resolve("notes.txt"), "x");
        var policy = WorkingDirPolicy.unrestricted();

        assertThatThrownBy(() -> policy.validate(tmp.resolve("nope").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a directory");
        assertThatThrownBy(() -> policy.validate(file.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a directory");
    }

    @Test
    void aRootKeepsSessionsUnderIt() throws Exception {
        Path inside = Files.createDirectories(tmp.resolve("allowed/project"));
        Path outside = Files.createDirectories(tmp.resolve("elsewhere"));
        var policy = WorkingDirPolicy.within(tmp.resolve("allowed").toString());

        assertThat(policy.validate(inside.toString())).isEqualTo(inside.toRealPath().toString());
        assertThat(policy.validate(tmp.resolve("allowed").toString())).as("the root itself")
                .isEqualTo(tmp.resolve("allowed").toRealPath().toString());
        assertThatThrownBy(() -> policy.validate(outside.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must lie under");
        assertThatThrownBy(() -> policy.validate(tmp.resolve("allowed/../elsewhere").toString()))
                .as("no escaping by dot-dot")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDirectoryTheSessionAlreadyHasIsNotHeldAgainstTheRootAgain() throws Exception {
        Path inside = Files.createDirectories(tmp.resolve("allowed/project"));
        Path own = Files.createDirectories(tmp.resolve("home/alice/sessions/s1"));
        Path outside = Files.createDirectories(tmp.resolve("elsewhere"));
        var policy = WorkingDirPolicy.within(tmp.resolve("allowed").toString());
        var kept = java.util.Set.of(own.toRealPath().toString());

        assertThat(policy.validate(own.toString(), kept)).isEqualTo(own.toRealPath().toString());
        assertThat(policy.validate(inside.toString(), kept)).isEqualTo(inside.toRealPath().toString());
        assertThatThrownBy(() -> policy.validate(outside.toString(), kept))
                .as("a directory the session does not have is checked as ever")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");
        assertThatThrownBy(() -> policy.validate(own.toString()))
                .as("without it, the same directory is refused")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");

        Path gone = tmp.resolve("gone");
        assertThatThrownBy(() -> policy.validate(gone.toString(), java.util.Set.of(gone.toString())))
                .as("a kept directory still has to exist")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a directory");
        assertThatThrownBy(() -> policy.withChoice(false).validate(own.toString(), kept))
                .as("and without choice nothing validates")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mindconnect.working-dirs.choice");
    }

    @Test
    void aBlankRootMeansUnrestricted() {
        assertThat(WorkingDirPolicy.within(null).root()).isNull();
        assertThat(WorkingDirPolicy.within("").root()).isNull();
        assertThat(WorkingDirPolicy.within("/").root()).isEqualTo(Path.of("/"));
    }

    @Test
    void aPerUserRootIsCreatedOnFirstUseAndKeepsUsersApart() throws Exception {
        var policy = WorkingDirPolicy.within(tmp.resolve("users/{user}").toString());
        assertThat(policy.isPerUser()).isTrue();
        assertThat(policy.root()).isNull();
        assertThatThrownBy(() -> policy.validate(tmp.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forUser");

        var alice = policy.forUser("alice");
        assertThat(alice.isPerUser()).isFalse();
        assertThat(alice.root()).isEqualTo(tmp.resolve("users/alice"));
        assertThat(Files.isDirectory(tmp.resolve("users/alice"))).as("created on first use").isTrue();

        Path project = Files.createDirectories(tmp.resolve("users/alice/project"));
        Path bobs = Files.createDirectories(tmp.resolve("users/bob/project"));
        assertThat(alice.validate(project.toString())).isEqualTo(project.toRealPath().toString());
        assertThatThrownBy(() -> alice.validate(bobs.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must lie under");

        assertThat(WorkingDirPolicy.within(tmp.toString()).forUser("alice")).as("a plain root is the same for everyone")
                .satisfies(p -> assertThat(p.root()).isEqualTo(tmp.toAbsolutePath().normalize()));
        assertThatThrownBy(() -> policy.forUser(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUserIdBecomesOnePathSegment() {
        assertThat(WorkingDirPolicy.pathSafe("alice")).isEqualTo("alice");
        assertThat(WorkingDirPolicy.pathSafe("alice@example.com")).isEqualTo("alice_example.com");
        assertThat(WorkingDirPolicy.pathSafe("../etc")).as("no dot-dot, no separator").isEqualTo("_._etc");
        assertThat(WorkingDirPolicy.pathSafe("a/b")).isEqualTo("a_b");
        assertThat(WorkingDirPolicy.pathSafe(".hidden")).isEqualTo("_hidden");
    }

    @Test
    void tildeStandsForHome() {
        String home = System.getProperty("user.home");
        assertThat(WorkingDirPolicy.expand("~")).isEqualTo(Path.of(home));
        assertThat(WorkingDirPolicy.expand("~/src")).isEqualTo(Path.of(home, "src"));
        assertThat(WorkingDirPolicy.expand("$HOME/src")).isEqualTo(Path.of(home, "src"));
        assertThat(WorkingDirPolicy.expand("/opt/x")).isEqualTo(Path.of("/opt/x"));
    }

    @Test
    void withoutChoiceNoDirectoryValidates_andNothingStaysNothing() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        var policy = WorkingDirPolicy.within(tmp.toString()).withChoice(false);

        assertThat(policy.allowsChoice()).isFalse();
        assertThatThrownBy(() -> policy.validate(project.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mindconnect.working-dirs.choice");
        assertThat(policy.validate(null)).as("no directory is not a choice").isNull();
        assertThat(policy.validate(" ")).isNull();
    }

    @Test
    void aUsersPolicyKeepsTheSwitch_andTurningItOnAgainChangesNothingElse() throws Exception {
        var template = WorkingDirPolicy.within(tmp + "/home/{user}").withChoice(false);

        var alice = template.forUser("alice");
        assertThat(alice.allowsChoice()).isFalse();
        assertThat(alice.root()).isEqualTo(tmp.resolve("home/alice"));

        var open = template.withChoice(true);
        assertThat(open.allowsChoice()).isTrue();
        assertThat(open.isPerUser()).isTrue();
        assertThat(open.withChoice(true)).as("nothing to change").isSameAs(open);
    }

    @Test
    void withoutChoiceTheBrowserShowsAndCreatesNothing() throws Exception {
        Files.createDirectories(tmp.resolve("somewhere"));
        var browser = new WorkingDirBrowser(WorkingDirPolicy.within(tmp.toString()).withChoice(false));

        assertThatThrownBy(() -> browser.list("alice", tmp.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mindconnect.working-dirs.choice");
        assertThatThrownBy(() -> browser.create("alice", tmp.toString(), "new-folder"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tmp.resolve("new-folder")).doesNotExist();
    }
}
