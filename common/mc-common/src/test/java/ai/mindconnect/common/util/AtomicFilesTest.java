package ai.mindconnect.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFilesTest {

    @TempDir
    Path dir;

    @Test
    void writesAndReplacesAndLeavesNoTemporaryFile() throws Exception {
        Path target = dir.resolve("sub/state.json");

        AtomicFiles.writeString(target, "{\"v\":1}");
        AtomicFiles.writeString(target, "{\"v\":2}");

        assertThat(Files.readString(target)).isEqualTo("{\"v\":2}");
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void aFailingWriteLeavesTheOldContentAndNoTemporaryFile() throws Exception {
        Path target = dir.resolve("state.json");
        AtomicFiles.writeString(target, "old");

        assertThatThrownBy(() -> AtomicFiles.write(target, out -> {
            out.write("half".getBytes());
            throw new IOException("disk full");
        })).hasMessage("disk full");

        assertThat(Files.readString(target)).isEqualTo("old");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).containsExactly(target);
        }
    }

    @Test
    void aReaderNeverSeesAnEmptyFile() throws Exception {
        Path target = dir.resolve("state.json");
        String content = "x".repeat(64_000);
        AtomicFiles.writeString(target, content);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> seen = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 300; i++) AtomicFiles.writeString(target, content);
            } catch (IOException e) {
                seen.set("writer failed: " + e);
            } finally {
                done.set(true);
            }
        });
        writer.start();
        while (!done.get()) {
            String read = Files.readString(target);
            if (read.length() != content.length()) {
                seen.compareAndSet(null, "read " + read.length() + " chars");
            }
        }
        writer.join();

        assertThat(seen.get()).isNull();
    }
}
