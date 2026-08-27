package ai.mindconnect.workflow.persist;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.StepInstance;
import ai.mindconnect.workflow.execution.WorkflowContext;
import ai.mindconnect.workflow.execution.WorkflowEventListener;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowInstance;
import ai.mindconnect.workflow.execution.WorkflowResult;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Suspend, throw the whole runtime away, rebuild from the snapshot, carry on.
 *
 * <p>Every test here resumes through a <em>second</em> executor with a fresh
 * context — the point of persistence is surviving a restart, and reusing the
 * live instance would prove nothing. What is not exercised is the JSON hop; that
 * lives with the file repository. What is exercised is the harder half: that the
 * rebuilt instance carries on exactly where the original stopped.
 */
public class WorkflowInstanceSnapshotsTest {

    @Test
    public void resumesAHaltInsideABlockAfterARestart() {
        WorkflowData wf = workflow("nested",
                block("block",
                        assign("before", "trace", "before"),
                        halt("pause"),
                        assign("after", "trace", "after")),
                assign("tail", "tail", "done"));

        Resumed resumed = suspendAndRestart(wf, Map.of());

        Assertions.assertThat(resumed.result().isSuccess()).isTrue();
        Assertions.assertThat(resumed.executed())
                .as("picks up inside the block, then leaves it")
                .containsSubsequence("after", "tail")
                .doesNotContain("before");
    }

    @Test
    public void resumesAHaltInsideAnIfBranchAfterARestart() {
        IfData check = new IfData();
        check.setName("check");
        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("mini: go");
        cond.setThenBlock(block("then",
                assign("in-branch", "trace", "in-branch"),
                halt("pause"),
                assign("after-halt", "trace", "after-halt")));
        check.setConditions(new IfData.Condition[]{cond});

        WorkflowData wf = workflow("if-halt",
                assign("seed", "go", "true"),
                check,
                assign("tail", "tail", "done"));

        Resumed resumed = suspendAndRestart(wf, Map.of());

        Assertions.assertThat(resumed.result().isSuccess()).isTrue();
        Assertions.assertThat(resumed.executed())
                .as("re-enters the branch it had taken; does not re-evaluate the if")
                .containsSubsequence("after-halt", "tail")
                .doesNotContain("in-branch");
    }

    @Test
    public void resumesAHaltInsideAForEachAndKeepsFinishedIterations() {
        ForEachData loop = new ForEachData();
        loop.setName("loop");
        loop.setLoopOver("items");
        loop.setRunVar("item");
        loop.setSteps(new ArrayList<>(List.of(
                assign("work", "seen", "yes"),
                haltIf("pause", "mini: item == 2"))));

        WorkflowData wf = workflow("loop-halt", loop);

        Resumed resumed = suspendAndRestart(wf, Map.of("items", List.of(1, 2, 3)));

        Assertions.assertThat(resumed.result().isSuccess()).isTrue();
        // Items 1 and 2 both ran "work" before the halt (2 is where it stopped).
        // Resuming re-enters iteration 2 *after* its work step and then runs the
        // one iteration left — so exactly one more "work", never a replay.
        Assertions.assertThat(resumed.executed().stream().filter("work"::equals).count())
                .as("finished work is not repeated")
                .isEqualTo(1);
        Assertions.assertThat(resumed.executed()).contains("pause"); // iteration 3 evaluates it
    }

    @Test
    public void keepsTheProcessEnvironmentOutOfTheSnapshot() {
        WorkflowData wf = workflow("env-halt", halt("pause"));

        WorkflowInstanceSnapshot snapshot = suspend(wf, Map.of()).snapshot();

        Assertions.assertThat(snapshot.getRoot().getVariables())
                .as("env holds the machine's whole environment — it must never be written out")
                .doesNotContainKey("env");
    }

    @Test
    public void refusesToResumeAWorkflowThatWasEditedWhileItSlept() {
        WorkflowData wf = workflow("edited",
                block("block", halt("pause"), assign("after", "trace", "after")));

        Suspended suspended = suspend(wf, Map.of());

        // Someone inserts a step above the halt while the instance sleeps: every
        // index in the snapshot now points one step to the left.
        WorkflowData edited = workflow("edited",
                assign("inserted", "x", "1"),
                block("block", halt("pause"), assign("after", "trace", "after")));

        Assertions.assertThatThrownBy(() ->
                        WorkflowInstanceSnapshots.restore(edited, suspended.snapshot(), freshContext()))
                .isInstanceOf(WorkflowInstanceSnapshots.DefinitionChangedException.class)
                .hasMessageContaining("edited since it was suspended");
    }

    @Test
    public void allowsAResumeWhenOnlyAStepsBodyChanged() {
        WorkflowData wf = workflow("tweaked",
                block("block", halt("pause"), assign("after", "trace", "after")));

        Suspended suspended = suspend(wf, Map.of());

        // The step list is untouched; one assignment's value changed. Every
        // pointer still lands where it did, so this must not block a resume.
        WorkflowData tweaked = workflow("tweaked",
                block("block", halt("pause"), assign("after", "trace", "CHANGED")));

        WorkflowInstance restored =
                WorkflowInstanceSnapshots.restore(tweaked, suspended.snapshot(), freshContext());

        Assertions.assertThat(restored).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private record Suspended(WorkflowInstanceSnapshot snapshot) {}

    private record Resumed(WorkflowResult result, List<String> executed) {}

    /** Runs until the workflow halts, then snapshots it. */
    private Suspended suspend(WorkflowData wf, Map<String, Object> params) {
        WorkflowExecutorService service =
                new WorkflowExecutorService(new DefaultWorkflowContextFactory());
        WorkflowResult result = service.executeWorkflow(wf, params);
        Assertions.assertThat(result.isHalted()).as("the workflow suspends").isTrue();
        return new Suspended(WorkflowInstanceSnapshots.capture(result.getInstance(), 0L));
    }

    /**
     * Suspends, then rebuilds and continues through a brand-new executor and
     * context — everything the first run held in memory is gone.
     */
    private Resumed suspendAndRestart(WorkflowData wf, Map<String, Object> params) {
        Suspended suspended = suspend(wf, params);

        WorkflowContext context = freshContext();
        WorkflowInstance restored =
                WorkflowInstanceSnapshots.restore(wf, suspended.snapshot(), context);

        // The restored instance already carries its own (fresh) context, so that
        // is where the listener has to go — continueWorkflow only builds a
        // context for an instance that has none.
        List<String> executed = new ArrayList<>();
        context.addEventListener(new WorkflowEventListener() {
            @Override
            public void beforeStepExecute(StepInstance<?> instance) {
                executed.add(instance.getConfig().getName());
            }
        });

        WorkflowExecutorService service =
                new WorkflowExecutorService(new DefaultWorkflowContextFactory());
        return new Resumed(service.continueWorkflow(restored, Map.of()), executed);
    }

    private static WorkflowContext freshContext() {
        return new DefaultWorkflowContextFactory().instantiate("restored");
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
