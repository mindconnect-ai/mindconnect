package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.WorkflowData;

/**
 * Loads a named workflow from the registry and executes it as a child container,
 * mapping variables from the current scope into the called workflow's scope.
 */
public class CallWorkflowStep extends BaseStepContainerInstance<CallWorkflowData> {

    @Override
    public void init(CallWorkflowData config, StepContainerInstance<?> parent, WorkflowContext context) {
        WorkflowDefinitionRegistry registry = context.getWorkflowDefinitionRegistry();
        if (registry == null) {
            throw new IllegalStateException("No WorkflowDefinitionRegistry configured");
        }
        WorkflowData workflowData = registry.getWorkflowDefinition(config.getWorkflow());
        if (workflowData == null) {
            throw new IllegalStateException("Workflow not found in registry: " + config.getWorkflow());
        }
        workflowData.setAssignResultToVar(config.getAssignResultToVar());
        config.addSteps(workflowData);
        super.init(config, parent, context);
    }

    @Override
    public void execute() throws Exception {
        // Map parameters from calling scope into this workflow's scope
        for (var entry : getConfig().getAssignParams().entrySet()) {
            Object value;
            VariableScope.Variable v = getVariableScope().getVariable(entry.getValue());
            if (v != null) {
                value = v.getValue();
            } else {
                value = getStringVariableReplacer().replaceVars(entry.getValue(),
                        getVariableScope()::getVariableValue);
            }
            getVariableScope().assignValue(entry.getKey(), value, getExpressionResolver());
        }

        super.execute();

        // Propagate the called workflow's result
        if (!getStepInstances().isEmpty()) {
            Object calledResult = getStepInstances().get(0).getResult();
            logDebug("called workflow '%s' returned: %s", getConfig().getWorkflow(), calledResult);
            setResult(calledResult);
        }
    }
}
