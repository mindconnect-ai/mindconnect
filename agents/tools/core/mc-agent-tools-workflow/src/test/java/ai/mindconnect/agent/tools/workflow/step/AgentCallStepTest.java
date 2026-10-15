package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.spi.SpiWorkflowContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent-call step end to end through the real engine: the configurer is
 * discovered via this module's ServiceLoader file, the message resolves
 * workflow variables, and the agent's answer becomes the step result. The
 * agent runtime is faked through the {@link AgentInvokers} holder — exactly
 * the seam the host app's auto-configuration fills in production.
 */
class AgentCallStepTest {

    record Call(String agent, String message) {}

    @AfterEach
    void tearDown() {
        AgentInvokers.clear();
    }

    private static WorkflowData workflow() {
        AgentCallData call = new AgentCallData();
        call.setName("ask");
        call.setAgent("poet");
        call.setMessage("Write a poem about ${topic}");
        call.setAssignResultToVar("answer");

        WorkflowData wf = new WorkflowData();
        wf.setName("agent-call-test");
        wf.setResultFrom("answer");
        wf.addSteps(call);
        wf.declareParams("topic");
        return wf;
    }

    @Test
    void callsTheAgentWithTheResolvedMessageAndYieldsItsAnswer() {
        List<Call> calls = new ArrayList<>();
        AgentInvokers.set((agent, message) -> {
            calls.add(new Call(agent, message));
            return "Roses are red";
        });

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow(), Map.of("topic", "spring"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("Roses are red");
        assertThat(calls).containsExactly(new Call("poet", "Write a poem about spring"));
    }

    @Test
    void failsDescriptivelyWhenNoAgentRuntimeIsAvailable() {
        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow(), Map.of("topic", "spring"));

        assertThat(result.isError()).isTrue();
        assertThat(String.valueOf(result.getError())).contains("AgentInvoker");
    }

    @Test
    void failsDescriptivelyWhenNoAgentIsConfigured() {
        AgentInvokers.set((agent, message) -> "unused");
        WorkflowData wf = workflow();
        ((AgentCallData) wf.getSteps().get(0)).setAgent(null);

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(wf, Map.of("topic", "spring"));

        assertThat(result.isError()).isTrue();
        assertThat(String.valueOf(result.getError())).contains("neither agent nor agentSpec configured");
    }
}
