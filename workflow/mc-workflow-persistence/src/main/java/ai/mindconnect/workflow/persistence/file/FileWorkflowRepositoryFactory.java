package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.nio.file.Path;

/** Workflows as JSON files under {@code <baseDir>/<partition>/workflows}. */
public class FileWorkflowRepositoryFactory implements WorkflowRepositoryFactory {

    private final Path baseDir;
    private final String partition;

    public FileWorkflowRepositoryFactory(Path baseDir, String partition) {
        this.baseDir = baseDir;
        this.partition = partition;
    }

    @Override
    public WorkflowDataRepository workflowDataRepository() {
        return new FileWorkflowDataRepository(baseDir, partition);
    }
}
