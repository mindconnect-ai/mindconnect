package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Assigns one or more variables in the parent scope. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AssignVariablesData extends BaseStepData {

    private List<VariableAssignment> variableAssignments = new ArrayList<>();
}
