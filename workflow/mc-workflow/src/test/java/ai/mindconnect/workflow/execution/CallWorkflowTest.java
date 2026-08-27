package ai.mindconnect.workflow.execution;


import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.mindconnect.workflow.execution.WorkflowTestHelper.*;

/**
 * Tests for CallWorkflowStep — invoking one workflow from another.
 *
 * Ported from the original CallWorkflowTest.
 * All tests that depended on HTTP endpoints or external services are removed.
 * The remaining test verifies pure in-process workflow composition.
 */
public class CallWorkflowTest {

    /**
     * A parent workflow calls a sub-workflow, passing a parameter and collecting
     * the sub-workflow's result. No I/O involved.
     *
     * Original: testCallWorkflow() — stripped of HTTP and file-write steps.
     */
    @Test
    public void calledWorkflowResultPropagatesBack() {
        // Sub-workflow: receives "query" and assigns "sub_result = processed: ${query}"
        WorkflowData sub = new WorkflowData();
        sub.setName("wf_sub");
        sub.declareParams("query");

        AssignVariablesData subStep = new AssignVariablesData();
        subStep.setName("processQuery");
        subStep.getVariableAssignments().add(
                new VariableAssignment("sub_result", "processed: ${query}"));
        subStep.setAssignResultToVar("sub_result");
        sub.addSteps(subStep);
        sub.setResultFrom("sub_result");

        // Parent workflow: calls sub-workflow and maps its result to "final_result"
        WorkflowData parent = new WorkflowData();
        parent.setName("wf_parent");
        parent.declareParams("question");

        CallWorkflowData call = new CallWorkflowData();
        call.setName("callSub");
        call.setWorkflow("wf_sub");
        call.addAssignParam("query", "${question}");
        call.setAssignResultToVar("call_result");
        parent.addSteps(call);
        parent.setResultFrom("call_result");

        // Register sub-workflow and execute parent
        WorkflowExecutorService service = createService();
        service.getContextFactory().getWorkflowDefinitionRegistry()
                .putWorkflowDefinition(sub.getName(), sub);

        WorkflowResult result = service.executeWorkflow(
                parent, Map.of("question", "Why is the sky blue?"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("processed: Why is the sky blue?");
    }

    /**
     * Verifies that parameters passed to the called workflow are correctly
     * scoped and do not leak back into the parent scope.
     */
    @Test
    public void calledWorkflowScopeIsIsolated() {
        WorkflowData sub = new WorkflowData();
        sub.setName("wf_scope_sub");
        AssignVariablesData subStep = new AssignVariablesData();
        subStep.setName("setInternal");
        subStep.getVariableAssignments().add(
                new VariableAssignment("internal_var", "sub_only"));
        sub.addSteps(subStep);
        sub.setResultFrom("internal_var");

        WorkflowData parent = new WorkflowData();
        parent.setName("wf_scope_parent");
        CallWorkflowData call = new CallWorkflowData();
        call.setName("callSub");
        call.setWorkflow("wf_scope_sub");
        call.setAssignResultToVar("sub_output");
        parent.addSteps(call);

        WorkflowExecutorService service = createService();
        service.getContextFactory().getWorkflowDefinitionRegistry()
                .putWorkflowDefinition(sub.getName(), sub);

        WorkflowResult result = service.executeWorkflow(parent, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getInstance().getVariableScope()
                .getVariableValue("sub_output")).isEqualTo("sub_only");
        // "internal_var" must not exist in the parent scope
        Assertions.assertThat(result.getInstance().getVariableScope()
                .getVariable("internal_var")).isNull();
    }

    /**
     * Verifies that calling a workflow not present in the registry
     * results in a clear error.
     */
    @Test
    public void missingWorkflowThrowsError() {
        WorkflowData parent = new WorkflowData();
        parent.setName("wf_missing");
        CallWorkflowData call = new CallWorkflowData();
        call.setName("callMissing");
        call.setWorkflow("nonexistent_wf");
        parent.addSteps(call);

        WorkflowResult result = createService().executeWorkflow(parent, Map.of());

        Assertions.assertThat(result.isError()).isTrue();
    }
}
