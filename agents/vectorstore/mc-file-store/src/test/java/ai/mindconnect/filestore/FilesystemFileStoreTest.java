package ai.mindconnect.filestore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemFileStoreTest {

    @TempDir
    Path dir;

    private FileStore store() {
        return FileStoreBackend.byType("filesystem").orElseThrow()
                .open(Map.of("dir", dir.toString()));
    }

    @Test
    void saveFindContentListDeleteRoundTrip() throws Exception {
        FileStore store = store();
        StoredFile saved = store.save("Report (v2).md", "text/markdown",
                new ByteArrayInputStream("# hello".getBytes(StandardCharsets.UTF_8)));

        assertThat(saved.id()).startsWith("file-");
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
        assertThat(store().find("../../etc/passwd")).isEmpty();
    }
}
