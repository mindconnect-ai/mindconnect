package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Probe: does resuming work when the halt sits INSIDE a container?
 *
 * <p>Existing halt tests only halt at the top level. This one halts inside a
 * block, so it exercises the case where the block's own step pointer would have
 * to survive the suspension.
 */
public class NestedHaltProbeTest {

    @Test
    public void haltInsideBlockResumesTheRestOfTheBlock() {
        WorkflowData wf = new WorkflowData();
        wf.setName("nested-halt");

        BlockData block = new BlockData();
        block.setName("block");
        block.setSteps(new ArrayList<>(List.of(
                assign("before", "trace", "before"),
                halt("pause"),
                assign("after", "trace", "after"))));

        wf.setSteps(new ArrayList<>(List.of(block, assign("tail", "tail", "tail"))));

        WorkflowExecutorService service =
                new WorkflowExecutorService(new DefaultWorkflowContextFactory());

        List<String> executed = new ArrayList<>();
        service.addEventListener(new WorkflowEventListener() {
            @Override
            public void beforeStepExecute(StepInstance<?> instance) {
                executed.add(instance.getConfig().getName());
            }
        });

        WorkflowResult r = service.executeWorkflow(wf, Map.of());
        Assertions.assertThat(r.isHalted()).as("halts inside the block").isTrue();
        System.out.println(">>> first pass:  " + executed);

        executed.clear();
        r = service.continueWorkflow(r.getInstance(), Map.of());
        System.out.println(">>> after continue: " + executed + "  state=" + r.getState());

        // The step after the halt, inside the same block, must run on resume.
        Assertions.assertThat(executed)
                .as("the step after the halt, inside the block")
                .contains("after");
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
}
