package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.Map;

/**
 * Shared test utilities.
 *
 * Deliberately dependency-free: no Jackson, no Groovy, no HTTP.
 * Workflows are built programmatically and executed directly — no
 * serialisation round-trip is needed at this layer.
 */
public class WorkflowTestHelper {

    // -----------------------------------------------------------------------
    // Execution helper
    // -----------------------------------------------------------------------

    /**
     * Builds a workflow via {@code supplier}, executes it with {@code params}
     * and returns the {@link WorkflowResult}.
     */
    public static WorkflowResult execute(
            WorkflowData workflowData,
            Map<String, Object> params
    ) {
        WorkflowExecutorService service = createService();
        return service.executeWorkflow(workflowData, params);
    }

    public static WorkflowExecutorService createService() {
        WorkflowContextFactory ctxFactory = new DefaultWorkflowContextFactory();
        ctxFactory.setWorkflowDefinitionRegistry(new SimpleInMemoryRegistry());
        // No ExpressionResolver — tests use only ${var} substitution and plain values
        return new WorkflowExecutorService(ctxFactory);
    }

    // -----------------------------------------------------------------------
    // Step builder helpers
    // -----------------------------------------------------------------------

    /** Creates a HaltData step that halts and optionally continues at {@code next}. */
    public static HaltData createHalt(String name, String resultExpression, String next) {
        HaltData halt = new HaltData();
        halt.setName(name);
        halt.setReturnResult(true);
        halt.setReturnResultExpression(resultExpression);
        halt.setNext(next);
        return halt;
    }

    /**
     * Creates an {@link AssignVariablesData} step that sets {@code varName = value}
     * in the parent scope — lightweight stand-in for ExecuteCodeData in unit tests.
     */
    public static AssignVariablesData createAssign(String name, String varName, String value) {
        AssignVariablesData assign = new AssignVariablesData();
        assign.setName(name);
        assign.getVariableAssignments().add(new VariableAssignment(varName, value));
        return assign;
    }

    // -----------------------------------------------------------------------
    // Minimal in-memory registry (no classpath scanning, no Jackson)
    // -----------------------------------------------------------------------

    public static class SimpleInMemoryRegistry implements WorkflowDefinitionRegistry {
        private final java.util.Map<String, WorkflowData> map = new java.util.LinkedHashMap<>();

        @Override
        public WorkflowData getWorkflowDefinition(String id) {
            return map.get(id);
        }

        @Override
        public void putWorkflowDefinition(String id, WorkflowData wd) {
            map.put(id, wd);
        }
    }
}
