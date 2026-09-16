package ai.mindconnect.workflow.persistence.memory;

import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

/** Workflows in memory — nothing survives the process. */
public class InMemoryWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        return new InMemoryWorkflowDataRepository();
    }
}
