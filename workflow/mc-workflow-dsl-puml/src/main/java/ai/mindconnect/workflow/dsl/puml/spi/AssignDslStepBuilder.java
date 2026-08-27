package ai.mindconnect.workflow.dsl.puml.spi;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.VariableAssignment;

import java.util.Map;

/**
 * Builds {@link AssignVariablesData} from DSL property lines.
 *
 * <p>Every property key becomes a variable name and every value becomes
 * the expression or literal assigned to it.
 *
 * <p>The reserved key {@code result} sets {@code assignResultToVar}.
 *
 * <p>Example:
 * <pre>
 *   :assign computeSum
 *     sum = spel: a + b
 *     result = sum
 *   ;
 * </pre>
 */
public class AssignDslStepBuilder implements DslStepBuilder {

    @Override
    public String typeName() {
        return "assign";
    }

    @Override
    public AssignVariablesData build(String name, Map<String, String> props) {
        AssignVariablesData data = new AssignVariablesData();
        data.setName(name);
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if ("assign-result".equalsIgnoreCase(entry.getKey()) || "result".equalsIgnoreCase(entry.getKey())) {
                data.setAssignResultToVar(entry.getValue());
            } else {
                data.getVariableAssignments().add(
                        new VariableAssignment(entry.getKey(), entry.getValue()));
            }
        }
        return data;
    }
}
