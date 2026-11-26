package ai.mindconnect.filestore;

import ai.mindconnect.agent.scope.ScopeSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * SPI for file-store backends, discovered via {@link ServiceLoader} — the same
 * pattern as the vector-store backends. {@code s3} or {@code db} backends live
 * in sibling modules and register this way. The filesystem store is not one:
 * the runtime builds it with its own mapper factory, which a backend found by
 * the {@link ServiceLoader} has no way to be given.
 */
public interface FileStoreBackend {

    /** Machine name ({@code "s3"}, {@code "db"}). */
    String type();

    /**
     * Opens the store for this backend-specific config (dir, bucket, jdbc url, ...);
     * it works in the namespace {@code scope} names for each call.
     */
    FileStore open(Map<String, String> config, ScopeSupplier scope);

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
