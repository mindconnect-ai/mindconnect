package ai.mindconnect.workflow.persistence.port;

/**
 * Creates the workflow store for one persistence backend — files, memory,
 * Postgres — so that whoever assembles an application picks a factory once
 * instead of switching over backends where the store is used.
 */
public interface WorkflowRepositoryFactory {

    WorkflowDataRepository workflowDataRepository();
}
