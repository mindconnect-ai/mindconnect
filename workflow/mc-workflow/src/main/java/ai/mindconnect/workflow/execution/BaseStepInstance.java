package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.util.StringVariableReplacer;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Convenient base for leaf step implementations.
 * Provides expression resolution, scoped logging, and state management.
 */
@Data
public abstract class BaseStepInstance<T extends StepData> implements StepInstance<T> {

    private static final Logger log = Logger.getLogger(BaseStepInstance.class.getName());

    private T config;
    private VariableScope variableScope;
    private WorkflowContext workflowContext;
    private Object result;
    private StepContainerInstance<?> container;
    private StepExecutionInfo stepExecutionInfo = new StepExecutionInfo();

    /**
     * Sub-steps this leaf ran itself. Empty for most steps; an {@link IfStep}
     * records the branch it took here, which is otherwise nowhere in the
     * execution record.
     */
    private List<StepInstance<?>> childInstances = new ArrayList<>();

    @Override
    public void init(T data, StepContainerInstance<?> parent, WorkflowContext context) {
        this.config = data;
        this.workflowContext = context;
        this.variableScope = new VariableScope(
                parent == null ? null : parent.getVariableScope(),
                data.getName()
        );
        this.container = parent;
    }

    @Override
    public void setState(ExecutionState state) {
        this.stepExecutionInfo.setState(state);
    }

    @Override
    public ExecutionState getState() {
        return this.stepExecutionInfo.getState();
    }

    // -----------------------------------------------------------------------
    // Expression resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves {@code expression}: handles {@code ${var}} substitution and
     * delegate to the configured {@link ExpressionResolver} for richer expressions.
     */
    public Object resolveExpression(String expression) {
        if (expression == null) return null;
//        if (expression.contains("${") && expression.contains("}")) {
//            return getStringVariableReplacer().replaceVars(expression, variableScope::getVariableValue);
//        }
        CompositeExpressionResolver resolver = getExpressionResolver();
        if (resolver != null && resolver.containsExpression(expression)) {
            return resolver.eval(variableScope, expression);
        }
        return expression;
    }

    public CompositeExpressionResolver getExpressionResolver() {
        return workflowContext != null ? workflowContext.getExpressionResolver() : null;
    }

    public StringVariableReplacer getStringVariableReplacer() {
        if (workflowContext != null && workflowContext.getStringVariableReplacer() != null) {
            return workflowContext.getStringVariableReplacer();
        }
        return new StringVariableReplacer();
    }

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------

    public void logDebug(String format, Object... params) {
        String message = String.format(format, params);
        stepExecutionInfo.addDebug(message);
        if (log.isLoggable(java.util.logging.Level.FINE)) {
            String scope = buildScopeHierarchy();
            log.fine(scope + " [" + config.getName() + "] " + message);
        }
    }

    private String buildScopeHierarchy() {
        List<String> names = variableScope.getScopeNamesHierarchy();
        Collections.reverse(names);
        return names.stream().collect(Collectors.joining(" -> "));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ":" + config;
    }
}
