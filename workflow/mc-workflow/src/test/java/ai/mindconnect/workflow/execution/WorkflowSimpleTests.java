package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.*;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.mindconnect.workflow.execution.WorkflowTestHelper.*;

/**
 * Basic workflow execution tests.
 *
 * Ported from the original mc-core-workflow WorkflowSimpleTests.
 * Groovy/script steps are replaced with {@link AssignVariablesStep} so
 * these tests run with zero external dependencies.
 */
public class WorkflowSimpleTests {

    // -----------------------------------------------------------------------
    // Block with result
    // -----------------------------------------------------------------------

    @Test
    public void blockProducesResult() {
        WorkflowData wd = createSimpleWf();
        WorkflowResult result = execute(wd, Map.of("question", "Why is the banana curved?", "x", "5"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("Why is the banana curved?_5");
    }

    @Test
    public void blockStepInstanceIsAccessible() {
        WorkflowData wd = createSimpleWf();
        WorkflowResult result = execute(wd, Map.of("question", "test", "x", "7"));

        BlockStep block = (BlockStep) result.getInstance().getStepInstances().get(0);
        Object innerResult = block.getStepInstances().get(0).getResult();
        Assertions.assertThat(result.getResult()).isEqualTo(innerResult);
    }

    // -----------------------------------------------------------------------
    // ForEach — sequential
    // -----------------------------------------------------------------------

    @Test
    public void forEachProducesListResult() {
        WorkflowData wd = createForEach();
        WorkflowResult result = execute(wd, forEachParams());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isInstanceOf(List.class);
        List<?> list = (List<?>) result.getResult();
        Assertions.assertThat(list).hasSize(3);
    }

    @Test
    public void forEachResultMatchesStepResult() {
        WorkflowData wd = createForEach();
        WorkflowResult result = execute(wd, forEachParams());

        ForEachStep forEachStep = (ForEachStep) result.getInstance().getStepInstances().get(0);
        Assertions.assertThat(result.getResult()).isEqualTo(forEachStep.getResult());
    }

    // -----------------------------------------------------------------------
    // ForEach — parallel
    // -----------------------------------------------------------------------

    @Test
    public void forEachParallelProducesListResult() {
        WorkflowData wd = createForEach();
        ForEachData forEachDef = (ForEachData) wd.getSteps().get(0);
        forEachDef.setParallel(true);

        WorkflowResult result = execute(wd, forEachParams());

        Assertions.assertThat(result.isSuccess()).isTrue();
        List<?> list = (List<?>) result.getResult();
        Assertions.assertThat(list).hasSize(3);
    }

    // -----------------------------------------------------------------------
    // Workflow builders
    // -----------------------------------------------------------------------

    /**
     * A workflow with a single Block that assigns a result by combining two input params.
     * Equivalent to the original createSimpleWf() but using AssignVariablesStep
     * instead of a Groovy ExecuteCodeStep.
     */
    public static WorkflowData createSimpleWf() {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_simple");
        wd.declareParams("question", "x");

        // Combines ${question}_${x} into a variable — pure string substitution
        AssignVariablesData assign = new AssignVariablesData();
        assign.setName("combine");
        assign.getVariableAssignments().add(
                new VariableAssignment("code_result", "${question}_${x}"));
        assign.setAssignResultToVar("code_result");

        BlockData block = new BlockData();
        block.setName("block");
        block.addSteps(assign);
        block.setResultFrom("code_result");
        block.setAssignResultToVar("code_result");

        wd.addSteps(block);
        wd.setResultFrom("code_result");
        return wd;
    }

    /**
     * A workflow with a ForEach that iterates over three items and produces
     * a result for each using variable substitution only.
     */
    public static WorkflowData createForEach() {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_for_each");
        wd.declareParams("question");

        AssignVariablesData assign = new AssignVariablesData();
        assign.setName("step");
        assign.getVariableAssignments().add(
                new VariableAssignment("code_result", "${current}"));
        assign.setAssignResultToVar("code_result");

        ForEachData forEach = new ForEachData();
        forEach.setName("for_loop");
        forEach.setRunVar("current");
        forEach.setIndexVar("i");
        // Static list — no script engine needed
        forEach.setLoopOver("items");
        forEach.addSteps(assign);
        forEach.setResultFrom("code_result");
        forEach.setAssignResultToVar("code_results");

        wd.addSteps(forEach);
        wd.setResultFrom("code_results");

        return wd;
    }

    /**
     * Returns params that include the "items" list required by {@link #createForEach()}.
     */
    public static Map<String, Object> forEachParams() {
        return Map.of(
                "question", "test",
                "items", List.of("item_a", "item_b", "item_c")
        );
    }

    // Override the no-arg forEachResult tests to supply items
    @Test
    public void forEachWithExplicitItems() {
        WorkflowData wd = createForEach();
        WorkflowResult result = execute(wd, forEachParams());

        Assertions.assertThat(result.isSuccess()).isTrue();
        List<?> list = (List<?>) result.getResult();
        @SuppressWarnings("unchecked")
        List<String> typedList = (List<String>) list;
        Assertions.assertThat(typedList).containsExactlyInAnyOrder("item_a", "item_b", "item_c");
    }
}
