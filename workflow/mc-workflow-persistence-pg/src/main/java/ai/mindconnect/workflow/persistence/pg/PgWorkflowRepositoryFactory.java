package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.nio.file.Path;

/**
 * Workflows as Postgres rows in one partition, created with their schema —
 * and, given the data directory a {@code FileWorkflowRepositoryFactory}
 * worked in, with what the file stores kept for the partition imported once
 * (see {@link PgWorkflowDataRepository#importFiles}).
 */
public class PgWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Sql sql;
    private final String partition;
    private final Path importFrom;

    public PgWorkflowRepositoryFactory(Sql sql, String partition) {
        this(sql, partition, null);
    }

    /**
     * @param importFrom the file stores' data directory, whose
     *                   {@code <partition>/workflows} is imported when the
     *                   tables have nothing for the partition yet; null for none
     */
    public PgWorkflowRepositoryFactory(Sql sql, String partition, Path importFrom) {
        this.sql = sql;
        this.partition = partition;
        this.importFrom = importFrom;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        PgWorkflowDataRepository store = new PgWorkflowDataRepository(sql, partition).initSchema();
        if (importFrom != null) {
            store.importFiles(FileWorkflowDataRepository.directory(importFrom, partition));
        }
        return store;
    }

    @Override
    public WorkflowInstanceRepository workflowInstanceRepository() {
        PgWorkflowInstanceRepository store = new PgWorkflowInstanceRepository(sql, partition).initSchema();
        if (importFrom != null) {
            store.importFiles(FileWorkflowInstanceRepository.directory(importFrom, partition));
        }
        return store;
    }
}
