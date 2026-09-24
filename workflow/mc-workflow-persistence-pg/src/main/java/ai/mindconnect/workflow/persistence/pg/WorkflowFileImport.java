package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * The one-time import of what a file store kept in a partition, shared by the
 * definition and the instance store: one row of {@code mc_workflow_import}
 * per partition and store says the files were looked at, so they are read
 * once — not again after the last row was deleted, which would bring back
 * deleted workflows and resumed runs on the next start.
 *
 * <p>The marker is inserted first, in the transaction that copies the rows:
 * a second process importing the same partition waits on it and then finds
 * it there. A partition that already has rows gets the marker and nothing
 * copied — what is in the table is newer than the files. The files are
 * never touched; going back to file persistence finds them as they were.
 */
final class WorkflowFileImport {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFileImport.class);

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_workflow_import (
                partition_key TEXT NOT NULL,
                store         TEXT NOT NULL,
                imported_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (partition_key, store)
            );
            """;

    private WorkflowFileImport() {
    }

    /**
     * Imports the {@code *.json} files of {@code directory} into {@code table},
     * once per partition, when the partition has no row there yet.
     *
     * @param read reads one file into the value {@code save} stores — or
     *             throws, and the file is skipped with a warning; it must not
     *             touch the database, as a failed statement would end the
     *             transaction for every file after it
     * @param save stores one value through the given transaction
     * @return how many files were imported
     */
    static <T> int once(Sql sql, String partition, String table, Path directory,
                        BiFunction<Path, String, T> read, SaveInTransaction<T> save) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        List<Path> files = jsonFiles(directory);
        int imported = sql.inTransaction(tx -> {
            if (tx.update("INSERT INTO mc_workflow_import (partition_key, store) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    partition, table) == 0) {
                return 0;
            }
            if (tx.scalar("SELECT count(*) FROM " + table + " WHERE partition_key = ?", Long.class, partition) > 0) {
                return 0;
            }
            int count = 0;
            for (Path file : files) {
                String name = file.getFileName().toString();
                T value;
                try {
                    value = read.apply(file, name.substring(0, name.length() - ".json".length()));
                } catch (Exception e) {   // the workflow serializer throws its IOExceptions unchecked
                    log.warn("Skipped {} while importing partition '{}' into {}: {}", file, partition, table, e.getMessage());
                    continue;
                }
                save.save(tx, value);
                count++;
            }
            return count;
        });
        if (imported > 0) {
            log.info("Imported {} file(s) of partition '{}' from {} into {}; the files are kept",
                    imported, partition, directory, table);
        }
        return imported;
    }

    /** Stores one value of an import through the importing transaction. */
    @FunctionalInterface
    interface SaveInTransaction<T> {
        void save(Sql tx, T value);
    }

    private static List<Path> jsonFiles(Path directory) {
        try (Stream<Path> listing = Files.list(directory)) {
            return listing
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }
}
