package ai.mindconnect.namespace.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filerepo.FileRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileNamespacePurgeTest {

    @TempDir
    Path dir;

    @Test
    void deletesTheNamespacesTreeAndNothingElse() throws Exception {
        Files.createDirectories(dir.resolve("acme/sessions/s1"));
        Files.writeString(dir.resolve("acme/sessions/s1/session.json"), "{}");
        Files.createDirectories(dir.resolve("local/sessions"));
        Files.createDirectories(dir.resolve("system/users"));

        new FileNamespacePurge(dir).purge(new Namespace("acme"));

        assertThat(dir.resolve("acme")).doesNotExist();
        assertThat(dir.resolve("local/sessions")).exists();
        assertThat(dir.resolve("system/users")).exists();
    }

    @Test
    void aNamespaceCreatedAgainUnderThePurgedIdOpensFreshWithALockOfItsOwn() {
        FileRepo before = FileRepo.open(dir, "acme");
        assertThat(before.resolve(".mc-partition.lock")).exists();

        new FileNamespacePurge(dir).purge(new Namespace("acme"));
        FileRepo after = FileRepo.open(dir, "acme");

        assertThat(after).as("not the old instance, whose lock guarded a deleted file").isNotSameAs(before);
        assertThat(dir.resolve("acme/.mc-partition.lock")).exists();
    }

    @Test
    void aNamespaceItNeverSawIsNothingToDo() {
        new FileNamespacePurge(dir).purge(new Namespace("never"));
        assertThat(dir).exists();
    }

    @Test
    void refusesTheInstallationsOwnDirectoryWhateverItIsAsked() throws Exception {
        Files.createDirectories(dir.resolve("system/users"));

        assertThatThrownBy(() -> new FileNamespacePurge(dir).purge(new Namespace("system")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.resolve("system/users")).exists();
    }
}
