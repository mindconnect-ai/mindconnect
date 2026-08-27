package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.util.StringVariableReplacer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared application context passed to every step during execution.
 * Holds all collaborators the engine needs — no Spring required.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowContext {

    private String id;

    /** Base directory used by file steps. */
    private String baseDir;

    private CompositeExpressionResolver expressionResolver;
    private StringVariableReplacer stringVariableReplacer;
    private Object scriptExecutor;
    private StepInstanceFactory stepInstanceFactory;

    /**
     * Optional writer for script {@code print()}/{@code println()} output.
     * {@code null} means fall back to {@code System.out}.
     */
    private Writer scriptOutputWriter;
    private JsonMapper jsonMapper;
    private WorkflowDefinitionRegistry workflowDefinitionRegistry;

    @Builder.Default
    private List<WorkflowEventListener> eventListeners = new ArrayList<>();

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
    // Event firing
    // -----------------------------------------------------------------------

    public void fireBeforeStepExecute(StepInstance<?> stepInstance) {
        eventListeners.forEach(l -> l.beforeStepExecute(stepInstance));
    }

    public void fireAfterStepExecute(StepInstance<?> stepInstance) {
        eventListeners.forEach(l -> l.afterStepExecute(stepInstance));
    }

    public void fireOnStepExecuteError(StepInstance<?> stepInstance, Exception ex) {
        eventListeners.forEach(l -> l.onStepExecuteError(stepInstance, ex));
    }
}
