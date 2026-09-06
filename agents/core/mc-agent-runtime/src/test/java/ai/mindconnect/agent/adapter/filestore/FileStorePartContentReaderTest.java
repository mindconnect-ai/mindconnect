package ai.mindconnect.agent.adapter.filestore;

import ai.mindconnect.agent.port.out.PartContentReader;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileStorePartContentReaderTest {

    /** The smallest FileStore that can hold a file: a map. */
    static final class MapFileStore implements FileStore {
        final Map<String, StoredFile> files = new HashMap<>();
        final Map<String, byte[]> bytes = new HashMap<>();
        boolean failReads;

        @Override
        public StoredFile save(String name, String contentType, InputStream content) throws IOException {
            byte[] data = content.readAllBytes();
            StoredFile file = new StoredFile(UUID.randomUUID().toString(), name, contentType, data.length, Instant.now());
            files.put(file.id(), file);
            bytes.put(file.id(), data);
            return file;
        }

        @Override public Optional<StoredFile> find(String id) { return Optional.ofNullable(files.get(id)); }

        @Override
        public InputStream content(String id) throws IOException {
            if (failReads) throw new IOException("disk on fire");
            return new ByteArrayInputStream(bytes.get(id));
        }

        @Override public List<StoredFile> list() { return List.copyOf(files.values()); }
        @Override public void delete(String id) { files.remove(id); bytes.remove(id); }
    }

    private final MapFileStore store = new MapFileStore();
    private final PartContentReader reader = new FileStorePartContentReader(store);

    @Test
    void readsBytesAndMediaTypeOfAStoredFile() throws Exception {
        StoredFile file = store.save("photo.png", "image/png",
                new ByteArrayInputStream("PNG-BYTES".getBytes(StandardCharsets.UTF_8)));

        PartContentReader.Content content = reader.read(file.id()).orElseThrow();

        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("PNG-BYTES");
        assertThat(content.mediaType()).isEqualTo("image/png");
    }

    @Test
    void anUnknownOrNullIdReadsAsEmpty() {
        assertThat(reader.read("nope")).isEmpty();
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    void anUnreadableFileReadsAsEmptyInsteadOfThrowing() throws Exception {
        StoredFile file = store.save("photo.png", "image/png", new ByteArrayInputStream(new byte[] {1}));
        store.failReads = true;

        assertThat(reader.read(file.id())).isEmpty();
    }
}
