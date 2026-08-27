package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.StepContainerData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.util.StringVariableReplacer;
import lombok.Data;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Base implementation for step containers (workflow, block, for-each, call-workflow).
 * Drives the sequential execution loop and manages child step lifecycle.
 */
@Data
public class BaseStepContainerInstance<T extends StepContainerData>
        implements StepContainerInstance<T> {

    private static final Logger log = Logger.getLogger(BaseStepContainerInstance.class.getName());

    private String uid = UUID.randomUUID().toString();
    private T config;
    private StepData currentStep;
    private int currentStepIndex = 0;
    private int nextStepIndex = 0;
    private Object result;
    private List<StepInstance<?>> stepInstances = new ArrayList<>();
    private WorkflowContext workflowContext;
    private VariableScope variableScope;
    private StepExecutionInfo stepExecutionInfo = new StepExecutionInfo();

    /**
     * The child that suspended inside itself, if any — the other half of the
     * resume pointer. {@link #nextStepIndex} says which step comes next; this
     * says that the step before it is not finished yet and must be re-entered
     * before the container moves on.
     */
    private StepInstance<?> haltedChild;

    // -----------------------------------------------------------------------
    // StepInstance — init & state
    // -----------------------------------------------------------------------

    @Override
    public void init(T data, StepContainerInstance<?> parent, WorkflowContext context) {
        this.config = data;
        this.workflowContext = context;
        this.variableScope = new VariableScope(
                parent == null ? null : parent.getVariableScope(),
                data.getName()
        );
        ensureStepNames(data);
    }

    @Override
    public void setState(ExecutionState state) {
        stepExecutionInfo.setState(state);
    }

    @Override
    public ExecutionState getState() {
        return stepExecutionInfo.getState();
    }

    /** For a container, what it ran inside itself is simply its children. */
    @Override
    public List<StepInstance<?>> getChildInstances() {
        return stepInstances;
    }

    // -----------------------------------------------------------------------
    // Step sequencing
    // -----------------------------------------------------------------------

    /** Auto-assigns UUIDs to any unnamed steps so jumpTo always has a target. */
    protected void ensureStepNames(StepContainerData container) {
        for (StepData step : container.getSteps()) {
            if (step.getName() == null || step.getName().isBlank()) {
                step.setName(UUID.randomUUID().toString());
            }
        }
    }

    public void resetCounters() {
        this.currentStep = null;
        this.currentStepIndex = 0;
        this.nextStepIndex = 0;
    }

    @Override
    public StepData nextStep() {
        List<StepData> list = getSteps();
        int lastIndex = list.size() - 1;
        if (nextStepIndex <= lastIndex) {
            currentStepIndex = nextStepIndex;
            nextStepIndex++;
        } else {
            return null;
        }
        currentStep = list.get(currentStepIndex);
        return currentStep;
    }

    protected List<StepData> getSteps() {
        return this.config.getSteps();
    }

    @Override
    public void jumpTo(String stepName) {
        List<StepData> steps = getSteps();
        for (int i = 0; i < steps.size(); i++) {
            if (stepName.equals(steps.get(i).getName())) {
                nextStepIndex = i;
                logDebug("jumpTo -> %s", stepName);
                return;
            }
        }
        throw new IllegalArgumentException(
                "jumpTo: step '" + stepName + "' not found in container '" + config.getName() + "'");
    }

    // -----------------------------------------------------------------------
    // Step execution
    // -----------------------------------------------------------------------

    public StepInstance<?> executeStep(StepData stepData) throws HaltException, StepExecutionException {
        return runStep(instantiate(stepData));
    }

    @SuppressWarnings("unchecked")
    private StepInstance<StepData> instantiate(StepData stepData) {
        StepInstance<StepData> stepInstance =
                (StepInstance<StepData>) workflowContext.getStepInstanceFactory().instantiate(stepData);
        stepInstance.init(stepData, this, workflowContext);
        return stepInstance;
    }

    /**
     * Runs one child instance — freshly created, or one being re-entered after a
     * suspension. Split out from {@link #executeStep} precisely so a resume can
     * reuse an existing instance rather than build a new one: an instance that
     * halted mid-way carries the state (its own step pointer, its scope) that
     * says where to pick up.
     */
    private StepInstance<?> runStep(StepInstance<?> stepInstance)
            throws HaltException, StepExecutionException {
        StepData stepData = stepInstance.getConfig();
        stepInstance.setState(ExecutionState.STARTED);
        beginExecution(stepInstance);

        try {
            logDebug("executing step: %s [%s]", stepData.getName(), stepData.getType());
            stepInstance.execute();
            Object stepResult = stepInstance.getResult();
            logDebug("finished step: %s — result: %s", stepData.getName(), stepResult);
            assignResultToVariable(stepData, stepResult);

            if (stepInstance instanceof HasNext hasNext && hasNext.getNext() != null) {
                jumpTo(hasNext.getNext());
            }
            finishExecution(stepInstance);
            return stepInstance;

        } catch (HaltException halt) {
            setResult(halt.getStepInstance() != null ? halt.getStepInstance().getResult() : null);
            finishWithError(stepInstance, halt);
            // Did this child suspend *inside itself*? Then it has to be re-entered
            // where it stopped, not restarted — our own pointer has already moved
            // past it, and rebuilding it would lose its progress and its scope.
            // The halt step itself is the exception: it is done, and the pointer
            // standing behind it is exactly right.
            if (halt.getStepInstance() != stepInstance) {
                this.haltedChild = stepInstance;
            }
            if (halt.getNext() != null) {
                jumpTo(halt.getNext());
            }
            throw halt;

        } catch (Exception ex) {
            finishWithError(stepInstance, ex);
            throw new StepExecutionException(stepInstance, ex);
        }
    }

    @Override
    public StepInstance<?> executeNextStep() throws HaltException, StepExecutionException {
        StepData stepData = nextStep();
        if (stepData == null) return null;
        StepInstance<?> instance = instantiate(stepData);
        // Recorded before it runs: whatever happens — success, error, halt — this
        // step ran, and the execution record should say so. (It used to record
        // the *halting* step here instead, which on a nested halt filed the deep
        // halt step as a direct child of every container up the chain.)
        stepInstances.add(instance);
        try {
            return runStep(instance);
        } catch (HaltException halt) {
            finishWithError(this, halt);
            setState(ExecutionState.HALTED);
            throw halt;
        } catch (StepExecutionException ex) {
            finishWithError(this, ex);
            setState(ExecutionState.ERROR);
            throw ex;
        }
    }

    // -----------------------------------------------------------------------
    // Container lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void prepareExecutionOfContainer() {
        beginExecution(this);
    }

    @Override
    public void finishUpExecutionOfContainer() {
        setResult(resolveContainerResult());
        finishExecution(this);
        logDebug("container done: %s — took %d ms, result: %s",
                config.getName(), stepExecutionInfo.getDurationInMs(), getResult());
    }

    @Override
    public void execute() throws Exception {
        prepareExecutionOfContainer();
        resumeHaltedChild();
        while (executeNextStep() != null) {
            // each call advances the step pointer
        }
        finishUpExecutionOfContainer();
    }

    /**
     * Finishes the child that was suspended mid-way, before the container walks
     * on. Without this, a resume would carry on at {@link #nextStepIndex} — which
     * already points <em>past</em> that child — and everything left inside it
     * would be skipped silently, while the workflow still reported success.
     */
    private void resumeHaltedChild() throws Exception {
        StepInstance<?> child = haltedChild;
        if (child == null) return;
        haltedChild = null;
        logDebug("resuming suspended step: %s", child.getConfig().getName());
        runStep(child);
    }

    protected Object resolveContainerResult() {
        String resultFrom = config.getResultFrom();
        if (resultFrom != null && !resultFrom.isBlank()) {
            VariableScope.Variable variable = variableScope.getVariable(resultFrom);
            if (variable != null) return variable.getValue();
            return resolveExpression(resultFrom);
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Lifecycle helpers
    // -----------------------------------------------------------------------

    // Each helper records the step's state *before* it fires the matching event:
    // a listener asking the instance what happened has to get the answer for
    // this event, not the one for the previous one. Firing first made every
    // finished step still report STARTED to afterStepExecute.

    protected void beginExecution(StepInstance<?> stepInstance) {
        StepExecutionInfo info = stepInstance.getStepExecutionInfo();
        info.setPositionInStepContainer(currentStepIndex);
        info.setStartTime(System.currentTimeMillis());
        info.setState(ExecutionState.STARTED);
        workflowContext.fireBeforeStepExecute(stepInstance);
    }

    protected void finishExecution(StepInstance<?> stepInstance) {
        StepExecutionInfo info = stepInstance.getStepExecutionInfo();
        info.setEndTime(System.currentTimeMillis());
        info.setState(ExecutionState.FINISHED);
        stepInstance.setState(ExecutionState.FINISHED);
        workflowContext.fireAfterStepExecute(stepInstance);
    }

    protected void finishWithError(StepInstance<?> stepInstance, Exception ex) {
        StepExecutionInfo info = stepInstance.getStepExecutionInfo();
        info.setEndTime(System.currentTimeMillis());
        ExecutionState state = ex instanceof HaltException ? ExecutionState.HALTED : ExecutionState.ERROR;
        info.setState(state);
        stepInstance.setState(state);
        if (state == ExecutionState.ERROR) {
            info.setErrorMessage(ex.getMessage());
            stepExecutionInfo.setErrorMessage("Error in step '" + stepInstance.getConfig().getName()
                    + "': " + ex.getMessage());
        }
        stepExecutionInfo.setState(state);
        workflowContext.fireOnStepExecuteError(stepInstance, ex);
    }

    // -----------------------------------------------------------------------
    // Expression resolution
    // -----------------------------------------------------------------------

    public Object resolveExpression(String expression) {
        if (expression == null) return null;
        if (expression.contains("${") && expression.contains("}")) {
            return getStringVariableReplacer().replaceVars(expression, variableScope::getVariableValue);
        }
        ExpressionResolver resolver = getExpressionResolver();
        if (resolver != null && resolver.containsExpression(expression)) {
            return resolver.eval(variableScope, expression);
        }
        return expression;
    }

    public Object evalExpression(String expression) {
        return getExpressionResolver().eval(variableScope, expression);
    }

    public ExpressionResolver getExpressionResolver() {
        return workflowContext != null ? workflowContext.getExpressionResolver() : null;
    }

    public StringVariableReplacer getStringVariableReplacer() {
        if (workflowContext != null && workflowContext.getStringVariableReplacer() != null) {
            return workflowContext.getStringVariableReplacer();
        }
        return new StringVariableReplacer();
    }

    // -----------------------------------------------------------------------
    // Variable assignment
    // -----------------------------------------------------------------------

    private void assignResultToVariable(StepData stepData, Object result) {
        String varName = stepData.getAssignResultToVar();
        if (varName != null && !varName.isBlank()) {
            VariableScope.Variable var = variableScope.assignValue(varName, result,
                    workflowContext.getExpressionResolver());
            logDebug("assigned result to var: %s", var.toShortString());
        }
    }

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------

    public void logDebug(String format, Object... params) {
        String message = String.format(format, params);
        stepExecutionInfo.addDebug(message);
        if (log.isLoggable(java.util.logging.Level.FINE)) {
            List<String> names = variableScope.getScopeNamesHierarchy();
            Collections.reverse(names);
            String scope = names.stream().collect(Collectors.joining(" -> "));
            log.fine(scope + " " + message);
        }
    }
}
