package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/** Workflows as JSON files under {@code <baseDir>/<partition>/workflows}, the partition asked on every call. */
public class FileWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Path baseDir;
    private final Supplier<String> partition;

    public FileWorkflowRepositoryFactory(Path baseDir, Supplier<String> partition) {
        this.baseDir = baseDir;
        this.partition = partition;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        return new FileWorkflowDataRepository(baseDir, partition);
    }

    @Override
    public WorkflowInstanceRepository workflowInstanceRepository() {
        return new FileWorkflowInstanceRepository(baseDir, partition);
    }
}
