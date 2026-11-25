package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Workflows as Postgres rows, created with their schema, in the partition
 * asked on every call — and, given the data directory a
 * {@code FileWorkflowRepositoryFactory} worked in, with what the file stores
 * kept there imported once for every partition found
 * (see {@link PgWorkflowDataRepository#importFiles}).
 */
public class PgWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Sql sql;
    private final Supplier<String> partition;
    private final Path importFrom;
    private final JsonMapperFactory json;

    public PgWorkflowRepositoryFactory(Sql sql, Supplier<String> partition, JsonMapperFactory json) {
        this(sql, partition, null, json);
    }

    /**
     * @param importFrom the file stores' data directory, whose
     *                   {@code <partition>/workflows} is imported for each
     *                   partition found there that the tables have nothing for
     *                   yet; null for none
     * @param json       the host's mapper factory, which both stores write their JSON with
     */
    public PgWorkflowRepositoryFactory(Sql sql, Supplier<String> partition, Path importFrom, JsonMapperFactory json) {
        this.sql = sql;
        this.partition = partition;
        this.importFrom = importFrom;
        this.json = json;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        PgWorkflowDataRepository store = new PgWorkflowDataRepository(sql, partition, json).initSchema();
        for (String found : partitionsToImport()) {
            new PgWorkflowDataRepository(sql, () -> found, json)
                    .importFiles(FileWorkflowDataRepository.directory(importFrom, found));
        }
        return store;
    }

    @Override
    public WorkflowInstanceRepository workflowInstanceRepository() {
        PgWorkflowInstanceRepository store = new PgWorkflowInstanceRepository(sql, partition, json).initSchema();
        for (String found : partitionsToImport()) {
            new PgWorkflowInstanceRepository(sql, () -> found, json)
                    .importFiles(FileWorkflowInstanceRepository.directory(importFrom, found));
        }
        return store;
    }

    /**
     * The partitions the file stores kept workflows for: which one a call works
     * in is not known when the store is built, so each is imported up front.
     */
    private List<String> partitionsToImport() {
        if (importFrom == null || !Files.isDirectory(importFrom)) return List.of();
        try (Stream<Path> dirs = Files.list(importFrom)) {
            return dirs.map(dir -> dir.getFileName().toString())
                    .filter(name -> name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"))
                    .filter(name -> Files.isDirectory(FileWorkflowDataRepository.directory(importFrom, name)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the partitions in " + importFrom, e);
        }
    }
}
