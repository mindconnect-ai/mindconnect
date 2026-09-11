package ai.mindconnect.filestore;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilesystemFileStoreTest {

    @TempDir
    Path dir;

    private FileStore store() {
        return FileStoreBackend.byType("filesystem").orElseThrow()
                .open(Map.of("baseDir", dir.toString(), "namespace", "test"));
    }

    @Test
    void saveFindContentListDeleteRoundTrip() throws Exception {
        FileStore store = store();
        StoredFile saved = store.save("Report (v2).md", "text/markdown",
                new ByteArrayInputStream("# hello".getBytes(StandardCharsets.UTF_8)));

        assertThat(saved.id().value()).startsWith("file-");
        assertThat(saved.name()).isEqualTo("Report _v2_.md");   // sanitised
        assertThat(saved.size()).isEqualTo(7);

        assertThat(store.find(saved.id())).contains(saved);
        assertThat(new String(store.content(saved.id()).readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("# hello");
        assertThat(store.list()).hasSize(1);

        store.delete(saved.id());
        assertThat(store.find(saved.id())).isEmpty();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void maliciousIdsCannotEscapeTheRoot() {
        // The id type refuses anything that is not a plain file-name-safe value …
        assertThatThrownBy(() -> FileId.of("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        // … and a well-formed id that was never saved is simply absent.
        assertThat(store().find(FileId.of("file-0123456789abcdef0123"))).isEmpty();
    }

    @Test
    void aFileIsOnlyVisibleInItsOwnNamespace() throws Exception {
        FileStore store = store();
        StoredFile saved = store.save("a.txt", "text/plain",
                new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        FileStore other = FileStoreBackend.byType("filesystem").orElseThrow()
                .open(Map.of("baseDir", dir.toString(), "namespace", "other"));
        assertThat(other.find(saved.id())).isEmpty();
        assertThat(other.list()).isEmpty();
        assertThat(store.list()).containsExactly(saved);
    }
}
