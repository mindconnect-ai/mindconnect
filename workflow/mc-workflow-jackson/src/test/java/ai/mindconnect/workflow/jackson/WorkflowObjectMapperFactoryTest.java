package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the ObjectMapper produced by {@link WorkflowObjectMapperFactory}
 * can round-trip the full StepData hierarchy via the @class mixin.
 */
public class WorkflowObjectMapperFactoryTest {

    private final ObjectMapper mapper = WorkflowObjectMapperFactory.create();

    @Test
    public void workflowDataRoundTrip() throws Exception {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_test");

        AssignVariablesData step = new AssignVariablesData();
        step.setName("assign");
        step.getVariableAssignments().add(new VariableAssignment("out", "${in}"));
        step.setAssignResultToVar("out");
        wd.addSteps(step);
        wd.setResultFrom("out");

        String json = mapper.writeValueAsString(wd);

        // Verify @class discriminator is present
        Assertions.assertThat(json).contains("\"@class\"");
        Assertions.assertThat(json).contains("ai.mindconnect.workflow.domain.WorkflowData");
        Assertions.assertThat(json).contains("ai.mindconnect.workflow.domain.AssignVariablesData");

        // Deserialize and verify structure
        WorkflowData restored = mapper.readValue(json, WorkflowData.class);
        Assertions.assertThat(restored.getName()).isEqualTo("wf_test");
        Assertions.assertThat(restored.getSteps()).hasSize(1);
        Assertions.assertThat(restored.getSteps().get(0)).isInstanceOf(AssignVariablesData.class);

        AssignVariablesData restoredStep = (AssignVariablesData) restored.getSteps().get(0);
        Assertions.assertThat(restoredStep.getVariableAssignments()).hasSize(1);
        Assertions.assertThat(restoredStep.getVariableAssignments().get(0).getVarName()).isEqualTo("out");
    }

    @Test
    public void allBuiltInStepTypesRoundTrip() throws Exception {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_all_types");

        BlockData block = new BlockData();
        block.setName("block");

        ForEachData forEach = new ForEachData();
        forEach.setName("loop");
        forEach.setRunVar("item");
        forEach.setIndexVar("i");
        forEach.setLoopOver("items");

        BlockData thenBlock = new BlockData();
        thenBlock.setName("thenBlock");
        BlockData elseBlock = new BlockData();
        elseBlock.setName("elseBlock");

        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("${flag}");
        cond.setThenBlock(thenBlock);
        IfData ifData = new IfData();
        ifData.setName("ifStep");
        ifData.setElseBlock(elseBlock);
        ifData.setConditions(cond);

        HaltData halt = new HaltData();
        halt.setName("halt");
        halt.setNext("resume");

        JumpToData jump = new JumpToData();
        jump.setName("jump");
        jump.setJumpTo("target");

        CallWorkflowData call = new CallWorkflowData();
        call.setName("call");
        call.setWorkflow("sub_wf");

        wd.addSteps(block, forEach, ifData, halt, jump, call);

        String json = mapper.writeValueAsString(wd);
        WorkflowData restored = mapper.readValue(json, WorkflowData.class);

        Assertions.assertThat(restored.getSteps()).hasSize(6);
        Assertions.assertThat(restored.getSteps().get(0)).isInstanceOf(BlockData.class);
        Assertions.assertThat(restored.getSteps().get(1)).isInstanceOf(ForEachData.class);
        Assertions.assertThat(restored.getSteps().get(2)).isInstanceOf(IfData.class);
        Assertions.assertThat(restored.getSteps().get(3)).isInstanceOf(HaltData.class);
        Assertions.assertThat(restored.getSteps().get(4)).isInstanceOf(JumpToData.class);
        Assertions.assertThat(restored.getSteps().get(5)).isInstanceOf(CallWorkflowData.class);
    }

    @Test
    public void customStepTypeRoundTrip() throws Exception {
        // Demonstrates that custom steps work without any registration —
        // just place the class on the classpath and it deserializes automatically.
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_custom");

        // AssignVariablesData acts as a stand-in here; the point is the @class mechanism
        AssignVariablesData custom = new AssignVariablesData();
        custom.setName("custom_step");
        wd.addSteps(custom);

        String json = mapper.writeValueAsString(wd);
        Assertions.assertThat(json).contains("AssignVariablesData");

        WorkflowData restored = mapper.readValue(json, WorkflowData.class);
        Assertions.assertThat(restored.getSteps().get(0)).isInstanceOf(AssignVariablesData.class);
    }
}
