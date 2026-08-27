package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Exposes every persisted workflow as an agent tool named {@code workflow_<id>}. The tool's
 * parameter schema is the workflow's declared input schema ({@code WorkflowData.getParams()},
 * an {@code ai.mindconnect.schema.Schema}), so the LLM knows exactly which inputs the workflow
 * expects. Executing the tool runs the workflow synchronously on the embedded engine.
 *
 * <p>The store directory comes from the {@code workflowDir} environment string (the same
 * directory the embedded workflow admin manages, {@code data/workflows} by default), so
 * workflows created or edited in the admin UI are what the tools run.
 *
 * <p>Fully dynamic: {@link #toolNames()} re-lists the store on every call (the registry
 * consults it live), so a workflow created in the admin UI is a tool on the very next
 * lookup — no restart. The definition is likewise re-read per execution, so edits apply
 * immediately.
 */
public final class WorkflowToolProvider implements MultiToolProvider {

    /** Machine namespace: tool names compose as {@code group() + "_" + workflowId}. */
    public static final String GROUP = "workflow";
    public static final String TOOL_NAME_PREFIX = GROUP + "_";

    private static final Logger log = LoggerFactory.getLogger(WorkflowToolProvider.class);

    private WorkflowDataRepository repository;

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public void bind(ToolEnvironment env) {
        String dir = env.getString("workflowDir").orElse("data/workflows");
        try {
            repository = new FileWorkflowDataRepository(Path.of(dir));
            log.info("WorkflowToolProvider: offering workflows from {} as tools (currently {})",
                    dir, toolNames().size());
        } catch (RuntimeException e) {
            log.warn("WorkflowToolProvider: cannot open workflow store at {} — no workflow tools available ({})",
                    dir, e.getMessage());
            repository = null;
        }
    }

    @Override
    public Set<String> toolNames() {
        if (repository == null) {
            return Set.of();
        }
        try {
            Set<String> names = new LinkedHashSet<>();
            for (String id : repository.listIds()) {
                names.add(TOOL_NAME_PREFIX + id);
            }
            return Set.copyOf(names);
        } catch (RuntimeException e) {
            log.warn("WorkflowToolProvider: cannot list workflow store ({})", e.getMessage());
            return Set.of();
        }
    }

    @Override
    public boolean isAvailable() {
        return repository != null;
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        if (repository == null || !toolName.startsWith(TOOL_NAME_PREFIX)) {
            return Optional.empty();
        }
        String workflowId = toolName.substring(TOOL_NAME_PREFIX.length());
        // scope may carry null userId/sessionId (the tool catalog resolves with nulls) — unused here.
        return repository.findById(workflowId)
                .map(wf -> new WorkflowTool(repository, workflowId, wf));
    }
}
