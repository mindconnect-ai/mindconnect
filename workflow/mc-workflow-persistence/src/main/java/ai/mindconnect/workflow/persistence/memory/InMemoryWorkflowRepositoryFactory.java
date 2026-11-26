package ai.mindconnect.workflow.persistence.memory;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Workflow definitions in memory — nothing survives the process. Instances
 * have no in-memory store; they go to files under {@code instancesDir},
 * a scratch directory for a runtime that keeps nothing.
 */
public class InMemoryWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Path instancesDir;
    private final Supplier<String> partition;
    private final JsonMapperFactory json;

    /** @param json the host's mapper factory, which the instances' files are written with */
    public InMemoryWorkflowRepositoryFactory(Path instancesDir, Supplier<String> partition, JsonMapperFactory json) {
        this.instancesDir = instancesDir;
        this.partition = partition;
        this.json = json;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        return new InMemoryWorkflowDataRepository();
    }

    @Override
    public WorkflowInstanceRepository workflowInstanceRepository() {
        return new FileWorkflowInstanceRepository(instancesDir, partition, json);
    }
}
