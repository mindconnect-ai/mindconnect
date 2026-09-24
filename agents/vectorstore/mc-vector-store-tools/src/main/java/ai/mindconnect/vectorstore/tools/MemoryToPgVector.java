package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.VectorStoreBackend;
import ai.mindconnect.vectorstore.memory.MemoryVectorBackend;
import ai.mindconnect.vectorstore.pgvector.PgVectorBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Moves one namespace's stores off the {@code memory} backend onto pgvector,
 * for a host whose vectors live in Postgres. The memory backend kept a store
 * as {@code <baseDir>/<namespace>/vector-stores/<store>.jsonl}; each such file
 * is read into the store's pgvector table when that table is still empty, and
 * the store's record then names {@code pgvector}. So does every template that
 * named {@code memory}, so that new stores follow.
 *
 * <p>A file whose chunks do not share one dimension, or whose dimension is not
 * the one the pgvector table was created with, is not imported: the store stays
 * on {@code memory}, where it keeps working, and the log says why. The files are
 * never changed or deleted. A store that already names {@code pgvector} is not
 * looked at again, so running this twice imports nothing twice.
 */
final class MemoryToPgVector {

    private static final Logger log = LoggerFactory.getLogger(MemoryToPgVector.class);
    private static final String MEMORY = "memory";
    private static final int BATCH = 500;

    private final VectorStoreBackend pgvector;
    /** The host's settings for a store: {@code baseDir} (where the memory files are), {@code url}, … */
    private final Map<String, String> hostConfig;
    private final VectorStoreTemplate defaultTemplate;

    MemoryToPgVector(VectorStoreBackend pgvector, Map<String, String> hostConfig, VectorStoreTemplate defaultTemplate) {
        this.pgvector = pgvector;
        this.hostConfig = hostConfig;
        this.defaultTemplate = defaultTemplate;
    }

    void move(Namespace namespace, VectorStoreRegistry registry) {
        Map<String, String> config = new HashMap<>(hostConfig);
        config.put(VectorStores.NAMESPACE_KEY, namespace.value());

        for (VectorStoreTemplate template : registry.templates()) {
            if (MEMORY.equals(template.backend())) {
                registry.saveTemplate(template.onBackend(PgVectorBackend.TYPE));
                log.info("Vector-store template '{}' of namespace '{}' now creates its stores in pgvector",
                        template.name(), namespace.value());
            }
        }

        // The registered stores on memory, and the files of every store — registered or not.
        Set<Path> seen = new HashSet<>();
        for (VectorStoreInstance instance : registry.instances()) {
            Path file = MemoryVectorBackend.storeFile(instance.name(), config);
            seen.add(file);
            if (MEMORY.equals(instance.backend()) && imported(namespace, instance.name(), file, config)) {
                registry.saveInstance(instance.onBackend(PgVectorBackend.TYPE));
            }
        }
        for (String storeName : new MemoryVectorBackend().listStores(config)) {
            Path file = MemoryVectorBackend.storeFile(storeName, config);
            if (seen.contains(file) || !hasChunks(file)) {
                continue;
            }
            // A store nobody registered: registered now, so that it is not imported again once emptied.
            if (pgvector.open(storeName, config).chunkCount() == 0 && imported(namespace, storeName, file, config)) {
                boolean chatStore = storeName.startsWith(VectorTools.SESSION_STORE_PREFIX);
                registry.registerInstance(VectorStoreInstance.fromTemplate(storeName,
                        defaultTemplate.onBackend(PgVectorBackend.TYPE),
                        chatStore ? VectorStoreInstance.Scope.SESSION : VectorStoreInstance.Scope.GLOBAL,
                        chatStore ? storeName.substring(VectorTools.SESSION_STORE_PREFIX.length()) : null));
            }
        }
    }

    /**
     * Whether the store may name pgvector from now on: its file is in pgvector
     * — imported now, or the table had chunks already — or there is no file.
     */
    private boolean imported(Namespace namespace, String storeName, Path file, Map<String, String> config) {
        if (!hasChunks(file)) {
            return true;
        }
        VectorStore target = pgvector.open(storeName, config);
        if (target.chunkCount() > 0) {
            log.info("Vector store '{}' of namespace '{}' has chunks in pgvector already; {} is not imported",
                    storeName, namespace.value(), file);
            return true;
        }
        List<VectorChunk> chunks;
        try {
            chunks = MemoryVectorBackend.readChunks(file);
        } catch (UncheckedIOException e) {
            log.warn("Vector store '{}' of namespace '{}' stays on the memory backend: {} cannot be read ({})",
                    storeName, namespace.value(), file, e.getMessage());
            return false;
        }
        if (chunks.isEmpty()) {
            return true;
        }
        int dimension = chunks.get(0).embedding() == null ? 0 : chunks.get(0).embedding().length;
        if (dimension == 0 || chunks.stream().anyMatch(c -> c.embedding() == null || c.embedding().length != dimension)) {
            log.warn("Vector store '{}' of namespace '{}' stays on the memory backend: the chunks in {} do not "
                    + "share one embedding dimension", storeName, namespace.value(), file);
            return false;
        }
        OptionalInt recorded = target.dimension();
        if (recorded.isPresent() && recorded.getAsInt() != dimension) {
            log.warn("Vector store '{}' of namespace '{}' stays on the memory backend: its pgvector table holds "
                    + "{}-dimensional embeddings, {} has {}-dimensional ones", storeName, namespace.value(),
                    recorded.getAsInt(), file, dimension);
            return false;
        }
        for (int from = 0; from < chunks.size(); from += BATCH) {
            target.upsert(chunks.subList(from, Math.min(from + BATCH, chunks.size())));
        }
        log.info("Imported {} chunk(s) of vector store '{}' of namespace '{}' from {} into pgvector (the file stays)",
                chunks.size(), storeName, namespace.value(), file);
        return true;
    }

    private static boolean hasChunks(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (java.io.IOException e) {
            return true;   // let the import say what is wrong with it
        }
    }
}
