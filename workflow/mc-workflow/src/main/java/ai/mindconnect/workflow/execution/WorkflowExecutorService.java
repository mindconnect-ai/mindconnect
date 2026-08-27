package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.WorkflowData;
import lombok.Data;

import java.util.*;

/**
 * Primary entry point for executing workflows.
 *
 * <p>Obtain an instance via {@link WorkflowExecutorService#WorkflowExecutorService(WorkflowContextFactory)}
 * or let a serialization module (e.g. mc-workflow-jackson) provide a
 * pre-configured factory via its own builder.
 */
@Data
public class WorkflowExecutorService {

    private final WorkflowContextFactory contextFactory;
    private List<WorkflowEventListener> eventListeners = new ArrayList<>();

    public WorkflowExecutorService(WorkflowContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    // -----------------------------------------------------------------------
    // Listener management
    // -----------------------------------------------------------------------

    public void addEventListener(WorkflowEventListener listener) {
        eventListeners.add(listener);
    }

    public boolean removeEventListener(WorkflowEventListener listener) {
        return eventListeners.remove(listener);
    }

    // -----------------------------------------------------------------------
    // Execution
    // -----------------------------------------------------------------------

    /**
     * Executes a workflow to completion (or until halted).
     *
     * @param workflowData the workflow definition
     * @param params       initial input variables; may be null or empty
     * @return a {@link WorkflowResult} describing the outcome
     */
    public WorkflowResult executeWorkflow(WorkflowData workflowData, Map<String, Object> params) {
        WorkflowContext context = createContext(workflowData.getName());
        WorkflowInstance instance = new WorkflowInstance();
        instance.init(workflowData, null, context);
        // Add all environment vars to scope
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        var props = System.getProperties().entrySet().stream().map(e -> Map.entry(String.valueOf(e.getKey()), String.valueOf(e.getValue())));
        props.forEach(e -> env.putIfAbsent(e.getKey(), e.getValue()));
        instance.getVariableScope().assignValue("env", env, context.getExpressionResolver());
        instance.assignParams(params);
        injectBuiltins(instance);
        return run(instance);
    }

    /**
     * Resumes a previously halted workflow.
     *
     * @param instance the halted instance returned by a prior call
     * @param params   additional variables to inject before resuming
     * @return an updated {@link WorkflowResult}
     */
    public WorkflowResult continueWorkflow(WorkflowInstance instance, Map<String, Object> params) {
        if (instance.getWorkflowContext() == null) {
            WorkflowContext context = createContext(instance.getConfig().getName());
            instance.setWorkflowContext(context);
        }
        instance.assignParams(params);
        injectBuiltins(instance);
        return run(instance);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Injects built-in variables that are always available in every workflow:
     * <ul>
     *   <li>{@code env} — an unmodifiable view of {@link System#getenv()}, so that
     *       expressions like {@code ${env.HOSTNAME}} or {@code env.MY_SECRET} work
     *       out of the box without the caller having to pass them as params.</li>
     * </ul>
     * Existing variables with the same name are NOT overwritten so that callers
     * can still override built-ins via explicit params if necessary.
     */
    private void injectBuiltins(WorkflowInstance instance) {
        // Only inject if not already provided by the caller
        if (instance.getVariableScope().getVariable("env") == null) {
            instance.assignParam("env", Collections.unmodifiableMap(System.getenv()));
        }
    }

    private WorkflowContext createContext(String id) {
        WorkflowContext context = contextFactory.instantiate(id);
        context.setEventListeners(new ArrayList<>(this.eventListeners));
        return context;
    }

    private WorkflowResult run(WorkflowInstance instance) {
        try {
            instance.execute();
            return WorkflowResult.success(instance);
        } catch (HaltException halt) {
            return WorkflowResult.halted(instance, halt);
        } catch (StepExecutionException ex) {
            return WorkflowResult.error(instance, ex);
        } catch (Exception ex) {
            return WorkflowResult.error(instance, ex);
        }
    }
}
