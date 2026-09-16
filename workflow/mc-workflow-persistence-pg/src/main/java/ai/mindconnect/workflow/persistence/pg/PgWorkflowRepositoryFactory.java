package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

/** Workflows as Postgres rows in one partition, created with their schema. */
public class PgWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Sql sql;
    private final String partition;

    public PgWorkflowRepositoryFactory(Sql sql, String partition) {
        this.sql = sql;
        this.partition = partition;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        return new PgWorkflowDataRepository(sql, partition).initSchema();
    }

    @Override
    public WorkflowInstanceRepository workflowInstanceRepository() {
        return new PgWorkflowInstanceRepository(sql, partition).initSchema();
    }
}
