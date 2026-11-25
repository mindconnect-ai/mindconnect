package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/** Workflows as JSON files under {@code <baseDir>/<partition>/workflows}, the partition asked on every call. */
public class FileWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Path baseDir;
    private final Supplier<String> partition;
    private final JsonMapperFactory json;

    /** @param json the host's mapper factory, which both stores write their JSON with */
    public FileWorkflowRepositoryFactory(Path baseDir, Supplier<String> partition, JsonMapperFactory json) {
        this.baseDir = baseDir;
        this.partition = partition;
        this.json = json;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        return new FileWorkflowDataRepository(baseDir, partition, json);
    }

    @Override
    public WorkflowInstanceRepository workflowInstanceRepository() {
        return new FileWorkflowInstanceRepository(baseDir, partition, json);
    }
}
