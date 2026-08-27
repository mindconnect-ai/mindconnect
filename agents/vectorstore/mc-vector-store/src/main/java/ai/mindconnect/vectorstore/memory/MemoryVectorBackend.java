package ai.mindconnect.vectorstore.memory;

import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.VectorStoreBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The built-in backend: chunks live as JSONL files on disk (embeddings are
 * computed once and never lost), and a store's vectors are loaded onto the
 * heap lazily on first search — so memory tracks the <em>active</em> stores,
 * not the total corpus. Idle stores are unloaded again after
 * {@code idleSeconds}; the next access simply reloads from disk.
 *
 * <p>Config keys (all optional):
 * <ul>
 *   <li>{@code dir} — base directory for store files (default {@code data/vector-stores})</li>
 *   <li>{@code maxChunksPerStore} — hard cap per store; upserts beyond it are
 *       rejected with a clear message instead of ballooning the JVM
 *       (default 100000, ~0.5–1 GB loaded). Larger corpora belong on a
 *       server-side backend such as pgvector.</li>
 *   <li>{@code idleSeconds} — unload stores unused for this long (default 600)</li>
 * </ul>
 */
public final class MemoryVectorBackend implements VectorStoreBackend {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorBackend.class);

    /**
     * JVM-wide: multiple backend instances (one per tool factory, tests, ...)
     * must share one heap copy per store file, or a writer through one
     * instance leaves stale vectors in another instance's loaded copy.
     * Keyed by the absolute store file path.
     */
    private static final Map<String, MemoryVectorStore> STORES = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService reaper;

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public VectorStore open(String storeId, Map<String, String> config) {
        ensureReaper(config);
        Path dir = Path.of(value(config, "dir", "data/vector-stores"));
        int maxChunks = Integer.parseInt(value(config, "maxChunksPerStore", "100000"));
        Path file = dir.resolve(sanitize(storeId) + ".jsonl").toAbsolutePath();
        return STORES.computeIfAbsent(file.toString(),
                key -> new MemoryVectorStore(storeId, file, maxChunks));
    }

    @Override
    public java.util.List<String> listStores(Map<String, String> config) {
        Path dir = Path.of(value(config, "dir", "data/vector-stores"));
        if (!java.nio.file.Files.isDirectory(dir)) {
            return java.util.List.of();
        }
        try (var files = java.nio.file.Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".jsonl"))
                    .map(n -> n.substring(0, n.length() - ".jsonl".length()))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            log.warn("Could not list vector stores in {}: {}", dir, e.toString());
            return java.util.List.of();
        }
    }

    private static synchronized void ensureReaper(Map<String, String> config) {
        if (reaper != null) {
            return;
        }
        long idleSeconds = Long.parseLong(value(config, "idleSeconds", "600"));
        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vector-store-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleAtFixedRate(() -> unloadIdle(idleSeconds), 1, 1, TimeUnit.MINUTES);
    }

    private static void unloadIdle(long idleSeconds) {
        long cutoff = System.currentTimeMillis() - idleSeconds * 1000;
        STORES.values().forEach(store -> {
            if (store.unloadIfIdleSince(cutoff)) {
                log.info("Unloaded idle vector store '{}' from memory", store.id());
            }
        });
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config == null ? null : config.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sanitize(String storeId) {
        return storeId.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
