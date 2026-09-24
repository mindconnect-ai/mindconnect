package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.pgvector.PgVectorBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The vector stores under Postgres persistence. Every namespace's registry is
 * a {@link PgVectorStoreRegistry}, filled once from the registry files the
 * namespace had before ({@code <baseDir>/<namespace>/vector-stores/templates|instances},
 * which stay). When the vectors live in pgvector too, opening a namespace also
 * moves its {@code memory} stores there ({@link MemoryToPgVector}).
 *
 * <p>Whether they can live in pgvector is {@link #pgvectorAvailable asked} of
 * the database once: a plain {@code postgres} image has no {@code vector}
 * extension, and then the vectors stay on the {@code memory} backend, in files,
 * with one warning that says how to change that.
 */
final class PostgresVectorStores {

    private static final Logger log = LoggerFactory.getLogger(PostgresVectorStores.class);

    /**
     * The answer per database, JVM-wide: a runtime builds its vector stores more
     * than once (the upload path, each tool factory), and asks — and warns — once.
     */
    private static final Map<DataSource, Boolean> PGVECTOR = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Opening a namespace imports and moves; one namespace at a time, JVM-wide, so
     * that the next one to open it finds the work done instead of doing it again.
     */
    private static final ReentrantLock OPENING = new ReentrantLock();

    private final Sql sql;
    private final Path baseDir;
    /** Null when the vectors are not in pgvector. */
    private final MemoryToPgVector move;

    PostgresVectorStores(Sql sql, Path baseDir, MemoryToPgVector move) {
        this.sql = sql;
        this.baseDir = baseDir;
        this.move = move;
    }

    /**
     * Whether the database has — or lets us create — the {@code vector}
     * extension. The first "no" for a database is logged as a warning naming
     * the fix; the answer holds until restart.
     */
    static boolean pgvectorAvailable(DataSource dataSource) {
        return PGVECTOR.computeIfAbsent(dataSource, ds -> PgVectorBackend.enableExtension(ds)
                .map(reason -> {
                    log.warn("Vector stores: the database has no pgvector extension ({}). Templates and "
                            + "instances are kept in Postgres, but the vectors stay in files under "
                            + "<data dir>/<namespace>/vector-stores (memory backend). To keep them in Postgres, "
                            + "install pgvector into the Postgres image and restart; the stores are moved over on "
                            + "first use. Keep the image's C library (an existing postgres:16-alpine data directory "
                            + "needs an Alpine build of pgvector — a Debian image changes the text collation).",
                            reason.replaceAll("\\s+", " ").strip());
                    return false;
                })
                .orElse(true));
    }

    /** The namespace's registry, with whatever the namespace kept in files brought over first. */
    VectorStoreRegistry open(Namespace namespace) {
        PgVectorStoreRegistry registry = new PgVectorStoreRegistry(sql, namespace);
        OPENING.lock();
        try {
            registry.initSchema();
            registry.importFrom(new FileVectorStoreRegistry(baseDir.resolve(namespace.value()).resolve("vector-stores")));
            if (move != null) {
                try {
                    move.move(namespace, registry);
                } catch (RuntimeException e) {
                    // The stores keep working where they are; the next start tries again.
                    log.warn("Vector stores of namespace '{}' could not all be moved to pgvector: {}",
                            namespace.value(), e.getMessage(), e);
                }
            }
        } finally {
            OPENING.unlock();
        }
        return registry;
    }
}
