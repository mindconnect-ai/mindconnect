package ai.mindconnect.filestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * SPI for file-store backends, discovered via {@link ServiceLoader} — the same
 * pattern as the vector-store backends. {@code filesystem} ships built-in;
 * {@code s3} or {@code db} backends live in sibling modules and register the
 * same way.
 */
public interface FileStoreBackend {

    /** Machine name ({@code "filesystem"}, {@code "s3"}, {@code "db"}). */
    String type();

    /** Opens the store for this backend-specific config (dir, bucket, jdbc url, ...). */
    FileStore open(Map<String, String> config);

    static List<FileStoreBackend> discover() {
        List<FileStoreBackend> backends = new ArrayList<>();
        for (FileStoreBackend backend : ServiceLoader.load(FileStoreBackend.class)) {
            backends.add(backend);
        }
        return backends;
    }

    static Optional<FileStoreBackend> byType(String type) {
        return discover().stream().filter(b -> b.type().equals(type)).findFirst();
    }
}
