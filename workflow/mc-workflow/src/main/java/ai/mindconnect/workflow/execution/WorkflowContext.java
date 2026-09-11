package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.util.StringVariableReplacer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Values the host application hands one run for its own steps — on whose
     * behalf the run happens, for instance — without the engine knowing their
     * types. Every step of the run sees them: parallel for-each blocks and
     * called workflows share this context. They are not part of a halted run's
     * snapshot, so a resumed run starts without them.
     */
    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

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
    // Attributes
    // -----------------------------------------------------------------------

    /** The attribute stored under {@code key}, or {@code null}. */
    public Object getAttribute(String key) {
        return attributeMap().get(key);
    }

    /** Stores {@code value} under {@code key}; a {@code null} value removes the attribute. */
    public void setAttribute(String key, Object value) {
        if (value == null) {
            attributeMap().remove(key);
        } else {
            attributeMap().put(key, value);
        }
    }

    /**
     * The attribute stored under the type's name, when it holds a value of
     * that type — the convention for attributes that are one typed object.
     */
    public <T> T getAttribute(Class<T> type) {
        Object value = attributeMap().get(type.getName());
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private Map<String, Object> attributeMap() {
        if (attributes == null) {
            attributes = new ConcurrentHashMap<>();
        }
        return attributes;
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
