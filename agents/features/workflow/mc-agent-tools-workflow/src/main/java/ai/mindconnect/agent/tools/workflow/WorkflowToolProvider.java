package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import java.util.Map;
import java.util.function.Supplier;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Exposes every persisted workflow as an agent tool named {@code workflow_<id>}. The tool's
 * parameter schema is the workflow's declared input schema ({@code WorkflowData.getParams()},
 * an {@code ai.mindconnect.schema.Schema}), so the LLM knows exactly which inputs the workflow
 * expects. Executing the tool runs the workflow synchronously on the embedded engine.
 *
 * <p>The workflows are the host's {@link WorkflowDataRepository} — the same store the workflow
 * admin writes to, whatever it is backed by. The provider never opens a store of its own: a
 * host that offers no repository offers no workflow tools, and says so by failing to bind.
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
    /** What a run's {@code env} variable holds; the process environment unless the host provides {@link EnvVarResolver}. */
    private Supplier<Map<String, String>> environment = WorkflowExecutorService::processEnvironment;

    @Override
    public String group() {
        return GROUP;
    }

    /** @throws IllegalStateException when the environment carries no {@link WorkflowDataRepository} */
    @Override
    public void bind(ToolEnvironment env) {
        environment = env.get(EnvVarResolver.class)
                .<Supplier<Map<String, String>>>map(vars -> vars.shared()::asMap)
                .orElse(WorkflowExecutorService::processEnvironment);
        repository = env.get(WorkflowDataRepository.class).orElseThrow(() -> new IllegalStateException(
                "WorkflowToolProvider: the tool environment carries no WorkflowDataRepository"
                        + " — no workflow tools available"));
        log.info("WorkflowToolProvider: offering the host's workflows as tools (currently {})",
                toolNames().size());
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
        // The run acts for this scope: its tool steps resolve their tools with it.
        // The tool catalog probes with null userId/sessionId, which a run tolerates.
        return repository.findById(workflowId)
                .map(wf -> new WorkflowTool(repository, workflowId, wf, scope, environment));
    }
}
