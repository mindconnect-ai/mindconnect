package ai.mindconnect.filerepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileWritesTest {

    @TempDir
    Path dir;

    @Test
    void writesAndReplacesAndLeavesNoTemporaryFile() throws Exception {
        Path target = dir.resolve("sub/state.json");

        FileWrites.writeString(target, "{\"v\":1}");
        FileWrites.writeString(target, "{\"v\":2}");

        assertThat(Files.readString(target)).isEqualTo("{\"v\":2}");
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void aFailingWriteLeavesTheOldContentAndNoTemporaryFile() throws Exception {
        Path target = dir.resolve("state.json");
        FileWrites.writeString(target, "old");

        assertThatThrownBy(() -> FileWrites.write(target, out -> {
            out.write("half".getBytes());
            throw new IOException("disk full");
        })).hasMessage("disk full");

        assertThat(Files.readString(target)).isEqualTo("old");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void recognisesItsOwnTemporaryFilesOnly() {
        assertThat(FileWrites.isTemporary(Path.of("sessions/.session.json.4711.tmp"))).isTrue();
        assertThat(FileWrites.isTemporary(Path.of("sessions/session.json"))).isFalse();
        assertThat(FileWrites.isTemporary(Path.of("notes.tmp"))).isFalse();
        assertThat(FileWrites.isTemporary(Path.of(FileRepo.LOCK_FILE))).isFalse();
    }
}
