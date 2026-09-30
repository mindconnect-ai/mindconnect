package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.runtime.service.workflows.ProjectWorkflowFiles;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The workflows a project brings with it, read fresh on every call — an edit
 * to the YAML is what the next run does, as with project agents and skills.
 * A file that does not read is kept with its error rather than dropped: the
 * model is told why the workflow is not there, which beats a workflow that
 * silently disappeared.
 */
final class ProjectWorkflows {

    private static final Logger log = LoggerFactory.getLogger(ProjectWorkflows.class);

    /** Past this the file is not a workflow and is not read. */
    static final long MAX_BYTES = 200_000;

    private ProjectWorkflows() {}

    /**
     * One workflow file: read, or not.
     *
     * @param workflow    the definition; {@code null} when the file does not read
     * @param description what the file says it does; may be {@code null}
     * @param error       why the file does not read; {@code null} when it does
     */
    record ProjectWorkflow(String name, WorkflowData workflow, String description, String error) {
        boolean ok() {
            return workflow != null;
        }
    }

    static List<ProjectWorkflow> list(String workingDir) {
        List<ProjectWorkflow> out = new ArrayList<>();
        for (ProjectWorkflowFiles.WorkflowFile file : ProjectWorkflowFiles.list(workingDir)) {
            out.add(read(file));
        }
        return out;
    }

    static Optional<ProjectWorkflow> find(String workingDir, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return ProjectWorkflowFiles.list(workingDir).stream()
                .filter(f -> f.name().equalsIgnoreCase(name.strip()))
                .findFirst()
                .map(ProjectWorkflows::read);
    }

    private static ProjectWorkflow read(ProjectWorkflowFiles.WorkflowFile file) {
        try {
            if (Files.size(file.file()) > MAX_BYTES) {
                return new ProjectWorkflow(file.name(), null, null, "the file is too large to be a workflow");
            }
            YamlWorkflowReader.Read read = YamlWorkflowReader.read(file.name(), file.file());
            return new ProjectWorkflow(file.name(), read.workflow(), read.description(), null);
        } catch (Exception e) {
            log.debug("Project workflow {} does not read: {}", file.file(), e.toString());
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ProjectWorkflow(file.name(), null, null, message.lines().findFirst().orElse(message));
        }
    }
}
