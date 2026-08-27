package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.*;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.mindconnect.workflow.execution.WorkflowTestHelper.*;

/**
 * Complex workflow execution tests: ForEach parallel, If/JumpTo, Halt/continue.
 *
 * Ported from the original WorkflowComplexTests.
 * All Groovy, HTTP and external-service dependencies are replaced with
 * plain AssignVariablesStep and HaltStep so these tests are fully self-contained.
 */
public class WorkflowComplexTests {

    // -----------------------------------------------------------------------
    // ForEach parallel
    // -----------------------------------------------------------------------

    @Test
    public void forEachParallelCollectsAllResults() {
        WorkflowData wd = createForEachParallel();
        WorkflowResult result = execute(wd, Map.of(
                "items", List.of("sales", "technical", "misc")));

        Assertions.assertThat(result.isSuccess()).isTrue();
        List<?> list = (List<?>) result.getInstance()
                .getStepInstances().get(0).getResult();
        Assertions.assertThat(list).hasSize(3);
    }

    // -----------------------------------------------------------------------
    // If / JumpTo
    // -----------------------------------------------------------------------

    @Test
    public void ifConditionTrueJumpsToThenBranch() {
        WorkflowData wd = createWorkflowWithIf();
        // Pre-supply "is_greater=true" so IfStep can evaluate it without an expression engine
        WorkflowResult result = execute(wd, Map.of("is_greater", "true"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        VariableScope scope = result.getInstance().getVariableScope();
        Assertions.assertThat(scope.getVariableValue("then_result")).isEqualTo("then_branch");
        Assertions.assertThat(scope.getVariableValue("else_result")).isNull();
    }

    @Test
    public void ifConditionFalseJumpsToElseBranch() {
        WorkflowData wd = createWorkflowWithIf();
        // Pre-supply "is_greater=false" — IfStep evaluates the condition string
        WorkflowResult result = execute(wd, Map.of("is_greater", "false"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        VariableScope scope = result.getInstance().getVariableScope();
        Assertions.assertThat(scope.getVariableValue("else_result")).isEqualTo("else_branch");
        Assertions.assertThat(scope.getVariableValue("then_result")).isNull();
    }

    // -----------------------------------------------------------------------
    // Halt / continue
    // -----------------------------------------------------------------------

    @Test
    public void workflowHaltsAndCanBeContinued() {
        WorkflowData wd = createWorkflowWithHalt();
        WorkflowExecutorService service = createService();

        WorkflowResult r = service.executeWorkflow(wd, Map.of("input", "hello"));

        // First execution halts at halt1
        Assertions.assertThat(r.isHalted()).isTrue();
        Assertions.assertThat(r.getResult()).isEqualTo("step1_value");

        // Continue — halts again at halt2
        r = service.continueWorkflow(r.getInstance(), Map.of());
        Assertions.assertThat(r.isHalted()).isTrue();

        // Continue — runs to completion
        r = service.continueWorkflow(r.getInstance(), Map.of());
        Assertions.assertThat(r.isSuccess()).isTrue();
    }

    @Test
    public void workflowHaltCountMatchesExpected() {
        WorkflowData wd = createWorkflowWithHalt();
        WorkflowExecutorService service = createService();

        WorkflowResult r = service.executeWorkflow(wd, Map.of("input", "hello"));
        int haltCount = 0;
        while (r.isHalted()) {
            haltCount++;
            r = service.continueWorkflow(r.getInstance(), Map.of());
        }
        Assertions.assertThat(haltCount).isEqualTo(2);
        Assertions.assertThat(r.isSuccess()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Workflow builders
    // -----------------------------------------------------------------------

    /**
     * ForEach over an "items" variable in parallel, each iteration assigns
     * the current item as its result.
     */
    public static WorkflowData createForEachParallel() {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_for_parallel");
        wd.declareParams("items");

        AssignVariablesData step = new AssignVariablesData();
        step.setName("assign_item");
        step.getVariableAssignments().add(new VariableAssignment("item_result", "${currentItem}"));
        step.setAssignResultToVar("item_result");

        ForEachData forEach = new ForEachData();
        forEach.setName("step_for");
        forEach.setParallel(true);
        forEach.setRunVar("currentItem");
        forEach.setIndexVar("ix");
        forEach.setLoopOver("items");
        forEach.addSteps(step);
        forEach.setResultFrom("item_result");
        forEach.setAssignResultToVar("loop_results");

        wd.addSteps(forEach);
        wd.setResultFrom("loop_results");
        return wd;
    }

    /**
     * Workflow that:
     * 1. Assigns sum = x + y (simulated by storing the sum via a pre-set variable)
     * 2. Evaluates an IfStep based on "calc_result"
     * 3. Branches to either "thenStep" or "elseStep"
     * 4. Both branches jump to "finish"
     *
     * Note: Since we have no expression engine in this module, we pre-compute
     * the boolean condition as an input variable "condition" so IfStep can
     * evaluate it as a plain variable lookup.
     */
    public static WorkflowData createWorkflowWithIf() {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_if");
        wd.addParam("x");
        wd.addParam("y");

        // thenBlock: executed when condition is true
        AssignVariablesData thenStep = new AssignVariablesData();
        thenStep.setName("thenStep");
        thenStep.getVariableAssignments().add(
                new VariableAssignment("then_result", "then_branch"));
        BlockData thenBlock = new BlockData();
        thenBlock.setName("thenBlock");
        thenBlock.addSteps(thenStep);

        // elseBlock: executed when no condition matches
        AssignVariablesData elseStep = new AssignVariablesData();
        elseStep.setName("elseStep");
        elseStep.getVariableAssignments().add(
                new VariableAssignment("else_result", "else_branch"));
        BlockData elseBlock = new BlockData();
        elseBlock.setName("elseBlock");
        elseBlock.addSteps(elseStep);

        // IfData: condition uses a pre-supplied boolean param
        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("${is_greater}");
        cond.setThenBlock(thenBlock);

        IfData ifData = new IfData();
        ifData.setName("ifStep");
        ifData.setConditions(cond);
        ifData.setElseBlock(elseBlock);

        // finish step always runs after the if
        AssignVariablesData finish = new AssignVariablesData();
        finish.setName("finish");
        finish.getVariableAssignments().add(
                new VariableAssignment("workflow_result", "done"));

        wd.addSteps(ifData, finish);
        return wd;
    }

    /**
     * Workflow that halts twice (halt1 → halt2) then finishes.
     * Each halt returns "step1_value" so the caller can assert on it.
     */
    public static WorkflowData createWorkflowWithHalt() {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_halt");
        wd.declareParams("input");

        AssignVariablesData step1 = new AssignVariablesData();
        step1.setName("step1");
        step1.getVariableAssignments().add(
                new VariableAssignment("step1_result", "step1_value"));
        step1.setAssignResultToVar("step1_result");

        HaltData halt1 = createHalt("halt1", "step1_result", "step2");

        AssignVariablesData step2 = new AssignVariablesData();
        step2.setName("step2");
        step2.getVariableAssignments().add(
                new VariableAssignment("step2_result", "step2_value"));

        HaltData halt2 = createHalt("halt2", "step1_result", "step3");

        AssignVariablesData step3 = new AssignVariablesData();
        step3.setName("step3");
        step3.getVariableAssignments().add(
                new VariableAssignment("final_result", "finished"));

        AssignVariablesData step4 = new AssignVariablesData();
        step4.setName("step4");
        step4.getVariableAssignments().add(
                new VariableAssignment("step4_result", "step4_value"));

        wd.addSteps(step1, halt1, step2, halt2, step3, step4);
        return wd;
    }
}
