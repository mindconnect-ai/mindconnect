package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.StepInstance;
import ai.mindconnect.workflow.execution.WorkflowContext;
import ai.mindconnect.workflow.execution.WorkflowEventListener;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowInstance;
import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshots;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The whole point, end to end: a workflow halts, everything in memory is thrown
 * away, and the run continues from a file.
 */
public class FileWorkflowInstanceRepositoryTest {

    @TempDir
    Path baseDir;

    @Test
    public void suspendsToDiskAndResumesFromIt() {
        WorkflowData wf = workflow("approval",
                assign("prepare", "amount", "42"),
                block("await",
                        halt("wait-for-approval"),
                        assign("release", "released", "yes")),
                assign("finish", "done", "true"));

        // --- pass one: run until it suspends, write it out --------------------
        WorkflowResult halted = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
                .executeWorkflow(wf, Map.of());
        Assertions.assertThat(halted.isHalted()).isTrue();

        FileWorkflowInstanceRepository repository = new FileWorkflowInstanceRepository(baseDir);
        String instanceId = repository.save(
                WorkflowInstanceSnapshots.capture(halted.getInstance(), 1_700_000_000_000L));

        // --- the process dies here -------------------------------------------

        // --- pass two: nothing survives but the file --------------------------
        FileWorkflowInstanceRepository reopened = new FileWorkflowInstanceRepository(baseDir);
        WorkflowInstanceSnapshot loaded = reopened.findById(instanceId).orElseThrow();

        Assertions.assertThat(loaded.getWorkflowName()).isEqualTo("approval");
        Assertions.assertThat(loaded.getRoot().getVariables())
                .containsEntry("amount", "42")
                .doesNotContainKey("env");

        List<String> executed = new ArrayList<>();
        WorkflowContext context = new DefaultWorkflowContextFactory().instantiate("resumed");
        context.addEventListener(new WorkflowEventListener() {
            @Override
            public void beforeStepExecute(StepInstance<?> instance) {
                executed.add(instance.getConfig().getName());
            }
        });

        WorkflowInstance restored = WorkflowInstanceSnapshots.restore(wf, loaded, context);
        WorkflowResult resumed = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
                .continueWorkflow(restored, Map.of());

        Assertions.assertThat(resumed.isSuccess()).isTrue();
        Assertions.assertThat(executed)
                .as("carries on inside the block, then leaves it — nothing is replayed")
                .containsSubsequence("release", "finish")
                .doesNotContain("prepare");
        Assertions.assertThat(restored.getVariableScope().getVariableValue("amount"))
                .as("variables from before the suspension came back")
                .isEqualTo("42");

        Assertions.assertThat(repository.delete(instanceId)).isTrue();
        Assertions.assertThat(repository.findById(instanceId)).isEmpty();
    }

    /**
     * The agent-loop shape: the workflow halts waiting for a user's reply, the
     * reply arrives long after the process that started the run has gone, and it
     * is handed in on resume as an ordinary input.
     */
    @Test
    public void resumesWithNewInputSuppliedAtContinueTime() {
        HaltData wait = halt("wait-for-user");
        wait.setResumeParams(Schema.object().prop("userMessage", Schema.string().multiline()));

        // The answering step sits at the top level on purpose: assignResultToVar
        // writes into the step's *immediate* container scope, so a step buried in
        // a block would leave its result where the workflow cannot see it.
        WorkflowData wf = workflow("agent",
                assign("greet", "reply", "hello, what can I do?"),
                block("turn", wait),
                code("answer", "\"you said: \" + userMessage"));

        WorkflowResult halted = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
                .executeWorkflow(wf, Map.of());
        Assertions.assertThat(halted.isHalted()).isTrue();

        FileWorkflowInstanceRepository repository = new FileWorkflowInstanceRepository(baseDir);
        String id = repository.save(WorkflowInstanceSnapshots.capture(halted.getInstance(), 1L));

        // --- the process dies; the user replies some time later ---------------

        WorkflowInstanceSnapshot loaded =
                new FileWorkflowInstanceRepository(baseDir).findById(id).orElseThrow();

        // The suspension can say what it is waiting for, without being rebuilt.
        Assertions.assertThat(WorkflowInstanceSnapshots.pendingHalt(wf, loaded))
                .get()
                .extracting(h -> h.getResumeParams().getProperties().keySet())
                .isEqualTo(java.util.Set.of("userMessage"));

        WorkflowInstance restored = WorkflowInstanceSnapshots.restore(
                wf, loaded, new DefaultWorkflowContextFactory().instantiate("resumed"));
        WorkflowResult resumed = new WorkflowExecutorService(new DefaultWorkflowContextFactory())
                .continueWorkflow(restored, Map.of("userMessage", "book me a flight"));

        Assertions.assertThat(resumed.isSuccess()).isTrue();
        Assertions.assertThat(restored.getVariableScope().getVariableValue("answered"))
                .as("the step after the halt read the value the resume brought in")
                .isEqualTo("you said: book me a flight");
    }

    @Test
    public void listsSuspendedInstancesNewestFirst() {
        FileWorkflowInstanceRepository repository = new FileWorkflowInstanceRepository(baseDir);
        repository.save(snapshot("a", 1_000L));
        repository.save(snapshot("b", 3_000L));
        repository.save(snapshot("a", 2_000L));

        Assertions.assertThat(repository.findAll())
                .extracting(WorkflowInstanceSnapshot::getSuspendedAt)
                .containsExactly(3_000L, 2_000L, 1_000L);
        Assertions.assertThat(repository.findByWorkflow("a")).hasSize(2);
    }

    @Test
    public void refusesToWriteAVariableThatCannotSurviveTheRoundTrip() {
        WorkflowInstanceSnapshot snapshot = snapshot("scripted", 1L);
        snapshot.getRoot().getVariables().put("handle", new Object() {
            // No properties: Jackson cannot write it, and neither could we read
            // it back as the same thing — which is the honest problem here.
        });

        Assertions.assertThatThrownBy(() -> new FileWorkflowInstanceRepository(baseDir).save(snapshot))
                .isInstanceOf(SnapshotSerializer.UnwritableSnapshotException.class)
                .hasMessageContaining("handle")
                .hasMessageContaining("cannot be written to JSON");
    }

    // -----------------------------------------------------------------------

    private static WorkflowInstanceSnapshot snapshot(String workflowName, long suspendedAt) {
        WorkflowInstanceSnapshot s = new WorkflowInstanceSnapshot();
        s.setWorkflowName(workflowName);
        s.setSuspendedAt(suspendedAt);
        s.setDefinitionFingerprint("fingerprint");
        s.setRoot(new ai.mindconnect.workflow.persist.FrameSnapshot());
        s.getRoot().setStepName(workflowName);
        return s;
    }

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

    private static CodeData code(String name, String script) {
        CodeData c = new CodeData();
        c.setName(name);
        c.setLanguage("mini");
        c.setCode(script);
        c.setAssignResultToVar("answered");
        return c;
    }
}
