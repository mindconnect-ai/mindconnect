package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.runtime.service.workflows.ProjectWorkflowFiles;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tools.workflow.step.CallerLimits;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.schema.SchemaValidator;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code run_workflow}: runs one of the workflows the session's project keeps
 * in {@code .mindconnect/workflows/}. The description lists them with their
 * inputs, read when the tool is made for the turn; the run reads the file
 * again, so an edit applies to the next call.
 *
 * <p>The run is held to the caller: its tool, agent and code steps reach only
 * what {@link CallerLimits} lets through, expressions are the restricted
 * MiniScript, code runs in the sandbox, and the server's environment is not
 * handed in.
 */
final class RunWorkflowTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RunWorkflowTool.class);

    private final String workingDir;
    private final ToolCallScope scope;
    private final CallerLimits limits;
    private final List<ProjectWorkflows.ProjectWorkflow> listed;

    RunWorkflowTool(ToolCallScope scope, CallerLimits limits) {
        this.scope = scope;
        this.workingDir = scope == null ? null : scope.workingDir();
        this.limits = limits;
        this.listed = ProjectWorkflows.list(workingDir);
    }

    @Override
    public String name() {
        return ProjectWorkflowFiles.TOOL;
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder("Runs a workflow this project defines in ")
                .append(ProjectWorkflowFiles.DIR)
                .append(" and returns its result. Pass the workflow's name and its inputs as 'input'. ");
        if (listed.isEmpty()) {
            return sb.append("The project defines none right now.").toString();
        }
        sb.append("Workflows:");
        for (ProjectWorkflows.ProjectWorkflow wf : listed) {
            sb.append("\n- ").append(wf.name());
            if (!wf.ok()) {
                sb.append(" (does not load: ").append(wf.error()).append(")");
                continue;
            }
            if (wf.description() != null) sb.append(": ").append(wf.description().strip());
            String inputs = inputs(wf.workflow().getParams());
            if (!inputs.isEmpty()) sb.append(" Input: ").append(inputs).append('.');
        }
        return sb.toString();
    }

    private static String inputs(Schema params) {
        if (params == null || params.getProperties() == null) return "";
        StringBuilder sb = new StringBuilder();
        params.getProperties().forEach((name, prop) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(name).append(" (").append(prop.getType().name().toLowerCase());
            if (params.getRequired() != null && params.getRequired().contains(name)) sb.append(", required");
            if (prop.getDefaultValue() != null) sb.append(", default ").append(prop.getDefaultValue());
            sb.append(')');
            if (prop.getDescription() != null) sb.append(" ").append(prop.getDescription());
        });
        return sb.toString();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("type", "string");
        List<String> names = listed.stream().filter(ProjectWorkflows.ProjectWorkflow::ok)
                .map(ProjectWorkflows.ProjectWorkflow::name).toList();
        if (!names.isEmpty()) workflow.put("enum", names);
        workflow.put("description", "The workflow's name.");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("description", "The workflow's inputs, by name.");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("workflow", workflow, "input", input));
        schema.put("required", List.of("workflow"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String name = arguments == null ? null : (String) arguments.get("workflow");
        if (name == null || name.isBlank()) {
            return "Error: name the workflow to run.";
        }
        ProjectWorkflows.ProjectWorkflow found = ProjectWorkflows.find(workingDir, name).orElse(null);
        if (found == null) {
            return "Error: this project defines no workflow '" + name + "'.";
        }
        if (!found.ok()) {
            return "Error: workflow '" + found.name() + "' does not load: " + found.error();
        }
        WorkflowData wf = found.workflow();
        Map<String, Object> input = input(arguments.get("input"), wf.getParams());
        if (wf.getParams() != null && wf.getParams().getProperties() != null
                && !wf.getParams().getProperties().isEmpty()) {
            List<String> errors = SchemaValidator.validate(wf.getParams(), input);
            if (!errors.isEmpty()) {
                return "Error: invalid input for workflow '" + found.name() + "': " + String.join("; ", errors);
            }
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (scope != null) attributes.put(ToolCallScope.class.getName(), scope);
        attributes.put(CallerLimits.class.getName(), limits);
        try {
            WorkflowResult result = new WorkflowExecutorService(RestrictedWorkflowContext.create())
                    .withEnvironment(Map::of)
                    .executeWorkflow(wf, input, attributes);
            if (result.isError()) {
                Throwable error = result.getError();
                String message = error != null && error.getMessage() != null ? error.getMessage()
                        : String.valueOf(error);
                return "Error: workflow '" + found.name() + "' failed: " + message;
            }
            if (result.isHalted()) {
                return "Error: workflow '" + found.name() + "' halted, which a project workflow cannot resume.";
            }
            Object value = result.getResult();
            String text = value == null ? "" : String.valueOf(value);
            return text.isBlank() ? "Workflow '" + found.name() + "' completed with no result." : text;
        } catch (RuntimeException e) {
            log.warn("Project workflow '{}' failed unexpectedly", found.name(), e);
            return "Error: workflow '" + found.name() + "' failed: " + e.getMessage();
        }
    }

    /** The model's input with each missing input's default filled in. */
    private static Map<String, Object> input(Object raw, Schema params) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> input.put(String.valueOf(k), v));
        }
        if (params != null && params.getProperties() != null) {
            params.getProperties().forEach((name, prop) -> {
                if (prop.getDefaultValue() != null) input.putIfAbsent(name, prop.getDefaultValue());
            });
        }
        return input;
    }
}
