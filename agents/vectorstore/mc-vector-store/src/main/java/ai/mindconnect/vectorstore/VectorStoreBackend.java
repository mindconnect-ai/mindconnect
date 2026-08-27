package ai.mindconnect.vectorstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * SPI for vector-store backends, discovered via {@link ServiceLoader} — the
 * same pattern as the agent runtime's tool factories. Each backend serves one
 * storage technology ({@code memory}, {@code pgvector}, ...) and opens any
 * number of named stores on it.
 */
public interface VectorStoreBackend {

    /** Machine name of this backend ({@code "memory"}, {@code "pgvector"}). */
    String type();

    /**
     * Opens (creating on first use) the store with the given id. {@code config}
     * carries backend-specific settings (directory, JDBC URL, limits) — see
     * the backend's documentation for its keys. Returned instances are cheap
     * handles; backends may share state between handles of the same id.
     */
    VectorStore open(String storeId, Map<String, String> config);

    /**
     * Ids of the stores that physically exist on this backend for the given
     * config (files on disk, tables in the database) — the admin UI's view of
     * reality, including stores created on the fly. Default: unknown/empty.
     */
    default java.util.List<String> listStores(Map<String, String> config) {
        return java.util.List.of();
    }

    /** All backends on the classpath, in registration order. */
    static List<VectorStoreBackend> discover() {
        List<VectorStoreBackend> backends = new ArrayList<>();
        for (VectorStoreBackend backend : ServiceLoader.load(VectorStoreBackend.class)) {
            backends.add(backend);
        }
        return backends;
    }
}
