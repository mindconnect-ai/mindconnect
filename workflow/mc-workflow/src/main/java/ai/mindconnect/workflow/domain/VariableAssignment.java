package ai.mindconnect.workflow.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A single variable assignment: {@code varName = expressionOrVarName}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VariableAssignment {

    private String varName;

    /** Either a literal value, a variable name, or an expression (e.g. spel:...). */
    private String expressionOrVarName;
}
