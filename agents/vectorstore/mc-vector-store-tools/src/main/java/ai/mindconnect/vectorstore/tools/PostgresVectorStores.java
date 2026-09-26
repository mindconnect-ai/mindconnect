package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.pgvector.PgEmbeddingIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The vector-store registry under Postgres persistence. Every namespace's
 * registry is a {@link PgVectorStoreRegistry}, filled once from the registry
 * files the namespace had before ({@code <baseDir>/<namespace>/vector-stores/templates|instances|members},
 * which stay).
 *
 * <p>Whether the index can live in the same database is {@link #pgvectorAvailable asked}
 * once: a plain {@code postgres} image has no {@code vector} extension, and
 * then the index stays in files, with one warning that says how to change that.
 */
final class PostgresVectorStores {

    private static final Logger log = LoggerFactory.getLogger(PostgresVectorStores.class);

    /**
     * The answer per database, JVM-wide: a runtime builds its vector stores more
     * than once (the upload path, each tool factory), and asks — and warns — once.
     */
    private static final Map<DataSource, Boolean> PGVECTOR = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Opening a namespace imports; one namespace at a time, JVM-wide, so that
     * the next one to open it finds the work done instead of doing it again.
     */
    private static final ReentrantLock OPENING = new ReentrantLock();

    private final Sql sql;
    private final Path baseDir;

    PostgresVectorStores(Sql sql, Path baseDir) {
        this.sql = sql;
        this.baseDir = baseDir;
    }

    /**
     * Whether the database has — or lets us create — the {@code vector}
     * extension. The first "no" for a database is logged as a warning naming
     * the fix; the answer holds until restart.
     */
    static boolean pgvectorAvailable(DataSource dataSource) {
        return PGVECTOR.computeIfAbsent(dataSource, ds -> PgEmbeddingIndex.enableExtension(ds)
                .map(reason -> {
                    log.warn("Vector stores: the database has no pgvector extension ({}). Templates, "
                            + "instances and members are kept in Postgres, but the embedding index stays in "
                            + "files under <data dir>/<namespace>/embeddings. To keep it in Postgres, install "
                            + "pgvector into the Postgres image and restart. Keep the image's C library (an "
                            + "existing postgres:16-alpine data directory needs an Alpine build of pgvector — a "
                            + "Debian image changes the text collation).",
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
        } finally {
            OPENING.unlock();
        }
        return registry;
    }
}
