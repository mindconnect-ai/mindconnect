package ai.mindconnect.agent.runtime.feature;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a runtime keeps its data. A builder setting rather than a feature:
 * every feature switches over it to pick its adapter, so the choice belongs
 * to the owner of each store, not to a central method.
 *
 * <p>{@link #dataDir()} is always set: file persistence roots everything
 * there; Postgres and in-memory persistence still root their file-based side
 * channels (vector-store files, scratch space) there. Workflows are no side
 * channel on Postgres — definitions and instances are tables, and what file
 * persistence left under {@code dataDir} is imported once — while in memory
 * the suspended instances still go to files there.
 */
public sealed interface Persistence {

    Path dataDir();

    /** Every store a file under {@code dataDir}. */
    record File(Path dataDir) implements Persistence {
        public File {
            Objects.requireNonNull(dataDir, "dataDir");
        }
    }

    /** Every store a table, over the given (ideally pooled) data source; side channels under {@code dataDir}. */
    record Postgres(DataSource dataSource, Path dataDir) implements Persistence {
        public Postgres {
            Objects.requireNonNull(dataSource, "dataSource");
            Objects.requireNonNull(dataDir, "dataDir");
        }
    }

    /** Nothing survives the runtime; side channels under a temp {@code dataDir}. */
    record InMemory(Path dataDir) implements Persistence {
        public InMemory {
            Objects.requireNonNull(dataDir, "dataDir");
        }
    }

    static Persistence file(Path dataDir) {
        return new File(dataDir);
    }

    static Persistence postgres(DataSource dataSource, Path dataDir) {
        return new Postgres(dataSource, dataDir);
    }

    static Persistence inMemory(Path tempDir) {
        return new InMemory(tempDir);
    }

    /** In-memory, with a fresh temp directory for the side channels (deleted by the OS, not by us). */
    static Persistence inMemory() {
        try {
            return new InMemory(java.nio.file.Files.createTempDirectory("mc-agent-runtime"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
