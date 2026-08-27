package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.WorkflowData;

/**
 * Provides named workflow definitions to the execution engine.
 * Implementations can load from classpath, a database, remote URLs, etc.
 */
public interface WorkflowDefinitionRegistry {

    /** Returns the workflow definition for the given id, or null if not found. */
    WorkflowData getWorkflowDefinition(String workflowDefinitionId);

    /** Registers a workflow definition programmatically. */
    void putWorkflowDefinition(String id, WorkflowData workflowData);
}
