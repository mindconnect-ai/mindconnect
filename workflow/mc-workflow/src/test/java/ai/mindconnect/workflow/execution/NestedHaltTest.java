package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Halting <em>inside</em> a container, and resuming from there.
 *
 * <p>The older halt tests only suspend at the top level, where resuming is
 * trivial: the container's step pointer already stands behind the halt step. One
 * level down it is not trivial at all — the container that was interrupted has
 * to be re-entered rather than rebuilt, or everything left inside it is skipped
 * while the workflow cheerfully reports success.
 */
public class NestedHaltTest {

    // -----------------------------------------------------------------------
    // Block
    // -----------------------------------------------------------------------

    @Test
    public void haltInsideBlockResumesTheRestOfTheBlock() {
        BlockData block = block("block",
                assign("before", "trace", "before"),
                halt("pause"),
                assign("after", "trace", "after"));

        WorkflowData wf = workflow("nested-halt", block, assign("tail", "tail", "done"));

        Run run = new Run(wf);
        Assertions.assertThat(run.first().isHalted()).isTrue();
        Assertions.assertThat(run.executed()).containsSubsequence("before", "pause");

        WorkflowResult resumed = run.next();

        Assertions.assertThat(resumed.isSuccess()).isTrue();
        Assertions.assertThat(run.executed())
                .as("the step after the halt, and only then the step after the block")
                .containsSubsequence("after", "tail");
        Assertions.assertThat(run.executed()).doesNotContain("before"); // not re-run
    }

    // -----------------------------------------------------------------------
    // If
    // -----------------------------------------------------------------------

    @Test
    public void haltInsideIfBranchResumesTheBranch() {
        IfData ifData = new IfData();
        ifData.setName("check");
        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("mini: go");
        cond.setThenBlock(block("then",
                assign("in-branch", "trace", "in-branch"),
                halt("pause"),
                assign("after-halt", "trace", "after-halt")));
        ifData.setConditions(new IfData.Condition[]{cond});

        WorkflowData wf = workflow("if-halt",
                assign("seed", "go", "true"),
                ifData,
                assign("tail", "tail", "done"));

        Run run = new Run(wf);
        Assertions.assertThat(run.first().isHalted()).isTrue();

        WorkflowResult resumed = run.next();

        Assertions.assertThat(resumed.isSuccess()).isTrue();
        Assertions.assertThat(run.executed())
                .as("resumes the branch it had taken, without re-running it")
                .containsSubsequence("after-halt", "tail")
                .doesNotContain("in-branch");
    }

    // -----------------------------------------------------------------------
    // For-each
    // -----------------------------------------------------------------------

    @Test
    public void haltInsideForEachResumesTheIterationAndFinishesTheLoop() {
        ForEachData loop = new ForEachData();
        loop.setName("loop");
        loop.setLoopOver("items");
        loop.setRunVar("item");
        loop.setSteps(new ArrayList<>(List.of(
                assign("work", "seen", "yes"),
                haltIf("pause", "mini: item == 2"))));

        // The list comes in as a param: the default context has no JSON mapper,
        // so "json:[…]" would stay a plain string here.
        WorkflowData wf = workflow("loop-halt", loop);

        Run run = new Run(wf, Map.of("items", List.of(1, 2, 3)));
        Assertions.assertThat(run.first().isHalted()).as("suspends in iteration 2").isTrue();
        // Iterations 0 and 1 ran their work step; the loop stopped inside 1.
        Assertions.assertThat(run.countOf("work")).isEqualTo(2);

        WorkflowResult resumed = run.next();

        Assertions.assertThat(resumed.isSuccess()).isTrue();
        Assertions.assertThat(run.countOf("work"))
                .as("only the remaining iteration runs — finished ones are not repeated")
                .isEqualTo(1);
    }

    @Test
    public void haltInsideParallelForEachFailsLoudly() {
        ForEachData loop = new ForEachData();
        loop.setName("loop");
        loop.setLoopOver("items");
        loop.setRunVar("item");
        loop.setParallel(true);
        loop.setSteps(new ArrayList<>(List.of(halt("pause"))));

        WorkflowData wf = workflow("parallel-halt", loop);

        WorkflowResult result = new Run(wf, Map.of("items", List.of(1, 2))).first();

        Assertions.assertThat(result.isError()).isTrue();
        Assertions.assertThat(rootCause(result.getError()))
                .hasMessageContaining("parallel")
                .hasMessageContaining("cannot be resumed");
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    /** Runs a workflow and records which steps each pass executed. */
    private static class Run {
        private final WorkflowExecutorService service =
                new WorkflowExecutorService(new DefaultWorkflowContextFactory());
        private final List<String> executed = new ArrayList<>();
        private final WorkflowData wf;
        private final Map<String, Object> params;
        private WorkflowResult result;

        Run(WorkflowData wf) {
            this(wf, Map.of());
        }

        Run(WorkflowData wf, Map<String, Object> params) {
            this.wf = wf;
            this.params = params;
            service.addEventListener(new WorkflowEventListener() {
                @Override
                public void beforeStepExecute(StepInstance<?> instance) {
                    executed.add(instance.getConfig().getName());
                }
            });
        }

        WorkflowResult first() {
            executed.clear();
            result = service.executeWorkflow(wf, params);
            return result;
        }

        /** Continues, and reports only what *this* pass executed. */
        WorkflowResult next() {
            if (result == null) first();
            executed.clear();
            result = service.continueWorkflow(result.getInstance(), Map.of());
            return result;
        }

        List<String> executed() {
            return executed;
        }

        long countOf(String stepName) {
            return executed.stream().filter(stepName::equals).count();
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    private static WorkflowData workflow(String name, StepData... steps) {
        WorkflowData wf = new WorkflowData();
        wf.setName(name);
        wf.setSteps(new ArrayList<>(List.of(steps)));
        return wf;
    }

    private static BlockData block(String name, StepData... steps) {
        BlockData b = new BlockData();
        b.setName(name);
        b.setSteps(new ArrayList<>(List.of(steps)));
        return b;
    }

    private static AssignVariablesData assign(String name, String var, String value) {
        AssignVariablesData a = new AssignVariablesData();
        a.setName(name);
        VariableAssignment va = new VariableAssignment();
        va.setVarName(var);
        va.setExpressionOrVarName(value);
        a.setVariableAssignments(new ArrayList<>(List.of(va)));
        return a;
    }


    private static HaltData halt(String name) {
        HaltData h = new HaltData();
        h.setName(name);
        return h;
    }

    private static HaltData haltIf(String name, String condition) {
        HaltData h = halt(name);
        h.setCondition(condition);
        return h;
    }
}
