package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.schema.SchemaValidator;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshots;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.spi.SpiWorkflowContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One workflow as one agent tool. The parameter schema handed to the LLM is the workflow's
 * declared input schema ({@link WorkflowData#getParams()} via {@link Schema#toMap()});
 * arguments are validated against it before the run. Execution is synchronous on the
 * embedded engine — the definition is re-read from the repository per call, so admin-UI
 * edits apply to the very next invocation.
 *
 * <p>Outcomes map to LLM-readable text: the workflow result on success, a descriptive error
 * on failure, and for a halt (human-in-the-loop suspension) a message naming the inputs the
 * halt is waiting for — resuming from an agent is not supported yet.
 */
final class WorkflowTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    private final WorkflowDataRepository repository;
    private final String workflowId;
    private final Schema params;
    private final String description;

    WorkflowTool(WorkflowDataRepository repository, String workflowId, WorkflowData snapshot) {
        this.repository = repository;
        this.workflowId = workflowId;
        this.params = snapshot.getParams() != null ? snapshot.getParams() : Schema.object();
        this.description = buildDescription(workflowId, snapshot);
    }

    @Override
    public String name() {
        return WorkflowToolProvider.TOOL_NAME_PREFIX + workflowId;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return params.toMap();
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        WorkflowData wf = repository.findById(workflowId).orElse(null);
        if (wf == null) {
            return "Workflow '" + workflowId + "' no longer exists.";
        }
        Map<String, Object> args = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);

        Schema declared = wf.getParams();
        if (declared != null && declared.getProperties() != null && !declared.getProperties().isEmpty()) {
            List<String> errors = SchemaValidator.validate(declared, args);
            if (!errors.isEmpty()) {
                return "Invalid arguments for workflow '" + workflowId + "': " + String.join("; ", errors);
            }
        }

        try {
            WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                    .executeWorkflow(wf, args);
            if (result.isError()) {
                Throwable error = result.getError();
                String message = error != null && error.getMessage() != null
                        ? error.getMessage() : String.valueOf(error);
                return "Workflow '" + workflowId + "' failed: " + message;
            }
            if (result.isHalted()) {
                return haltedMessage(wf, result);
            }
            Object value = result.getResult();
            String text = value == null ? "" : String.valueOf(value);
            return text.isBlank()
                    ? "The workflow '" + workflowId + "' completed successfully with no result."
                    : text;
        } catch (RuntimeException e) {
            log.warn("Workflow tool '{}' failed unexpectedly", workflowId, e);
            return "Workflow '" + workflowId + "' failed: " + e.getMessage();
        }
    }

    /** A halt is a human-in-the-loop suspension — tell the LLM what it is waiting for. */
    private String haltedMessage(WorkflowData wf, WorkflowResult result) {
        String waitingFor = Optional.ofNullable(result.getInstance())
                .flatMap(instance -> {
                    try {
                        return WorkflowInstanceSnapshots.pendingHalt(wf,
                                WorkflowInstanceSnapshots.capture(instance, System.currentTimeMillis()));
                    } catch (RuntimeException e) {
                        return Optional.<HaltData>empty();
                    }
                })
                .map(halt -> halt.getResumeParams() != null && halt.getResumeParams().getProperties() != null
                        ? String.join(", ", halt.getResumeParams().getProperties().keySet()) : "")
                .filter(s -> !s.isBlank())
                .map(s -> " It is waiting for input: " + s + ".")
                .orElse("");
        return "Workflow '" + workflowId + "' halted before completing." + waitingFor
                + " Resuming a halted workflow is not supported from agent tools yet — "
                + "a human can continue it in the workflow admin UI.";
    }

    private static String buildDescription(String workflowId, WorkflowData wf) {
        StringBuilder sb = new StringBuilder("Runs the workflow '").append(workflowId).append("'.");
        if (wf.getWorkflowType() != null && !wf.getWorkflowType().isBlank()) {
            sb.append(" Type: ").append(wf.getWorkflowType()).append(".");
        }
        Schema params = wf.getParams();
        if (params != null && params.getDescription() != null && !params.getDescription().isBlank()) {
            sb.append(" ").append(params.getDescription());
        }
        return sb.toString();
    }
}
