package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.VariableAssignment;

/**
 * Assigns one or more variables into the parent scope.
 * Each assignment's {@code expressionOrVarName} is resolved before writing.
 */
public class AssignVariablesStep extends BaseStepInstance<AssignVariablesData> {

    @Override
    public void execute() {
        Object lastValue = null;
        for (VariableAssignment assignment : getConfig().getVariableAssignments()) {
            Object value = resolveExpression(assignment.getExpressionOrVarName());
            VariableScope.Variable v = getVariableScope().assignValueToParentScope(
                    assignment.getVarName(), value, null);
            lastValue = value;
            logDebug("assigned %s", v.toShortString());
        }
        // Expose the last assigned value as the step result so assign_result_to_var works
        setResult(lastValue);
    }
}
