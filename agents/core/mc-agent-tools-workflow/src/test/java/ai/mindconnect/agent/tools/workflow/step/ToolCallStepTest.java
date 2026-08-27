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
 * The tool-call step end to end through the real engine: the configurer is
 * discovered via ServiceLoader, the JSON arguments resolve workflow variables,
 * and the tool's text result becomes the step result. The registry is faked
 * through the {@link ToolInvokers} holder — the seam the host app's
 * auto-configuration fills in production.
 */
class ToolCallStepTest {

    record Call(String tool, Map<String, Object> arguments) {}

    @AfterEach
    void tearDown() {
        ToolInvokers.clear();
    }

    private static WorkflowData workflow(String argumentsJson) {
        ToolCallData call = new ToolCallData();
        call.setName("search");
        call.setTool("web_search");
        call.setArguments(argumentsJson);
        call.setAssignResultToVar("hits");

        WorkflowData wf = new WorkflowData();
        wf.setName("tool-call-test");
        wf.setResultFrom("hits");
        wf.addSteps(call);
        wf.declareParams("topic");
        return wf;
    }

    @Test
    void callsTheToolWithResolvedJsonArgumentsAndYieldsItsResult() {
        List<Call> calls = new ArrayList<>();
        ToolInvokers.set((tool, args) -> {
            calls.add(new Call(tool, args));
            return "3 results";
        });

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow("{\"query\": \"${topic}\", \"limit\": 3}"),
                        Map.of("topic", "spring boot"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("3 results");
        assertThat(calls).containsExactly(
                new Call("web_search", Map.of("query", "spring boot", "limit", 3)));
    }

    @Test
    void argumentsResolvingToAMapArePassedThroughUnparsed() {
        List<Call> calls = new ArrayList<>();
        ToolInvokers.set((tool, args) -> {
            calls.add(new Call(tool, args));
            return "ok";
        });

        // A map built upstream (e.g. by a code step) — contains characters that
        // would break JSON interpolation.
        Map<String, Object> prepared = Map.of("content", "line \"one\"\nline two");
        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow("${writeArgs}"),
                        Map.of("topic", "unused", "writeArgs", prepared));

        assertThat(result.isSuccess()).as(String.valueOf(result.getError())).isTrue();
        assertThat(calls).containsExactly(new Call("web_search", prepared));
    }

    @Test
    void missingArgumentsMeanAnEmptyArgumentMap() {
        List<Call> calls = new ArrayList<>();
        ToolInvokers.set((tool, args) -> {
            calls.add(new Call(tool, args));
            return "ok";
        });

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow(null), Map.of("topic", "unused"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls).containsExactly(new Call("web_search", Map.of()));
    }

    @Test
    void invalidArgumentJsonFailsDescriptively() {
        ToolInvokers.set((tool, args) -> "unused");

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow("not json"), Map.of("topic", "unused"));

        assertThat(result.isError()).isTrue();
        assertThat(String.valueOf(result.getError())).contains("not a JSON object");
    }

    @Test
    void aToolErrorFailsTheStepInsteadOfMasqueradingAsSuccess() {
        // Tools report failure as text ("Error: …") — the step must propagate
        // that as a FAILED workflow, not hand the error string on as a result
        // (this is how file ingestion once "succeeded" with zero chunks).
        ToolInvokers.set((tool, args) -> "Error: no embeddings model configured");

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow("{}"), Map.of("topic", "unused"));

        assertThat(result.isError()).isTrue();
        assertThat(String.valueOf(result.getError()))
                .contains("web_search")
                .contains("no embeddings model configured");
    }

    @Test
    void failOnErrorFalseHandsTheErrorTextThroughAsBefore() {
        ToolInvokers.set((tool, args) -> "Error: inspect me downstream");

        ToolCallData call = new ToolCallData();
        call.setName("search");
        call.setTool("web_search");
        call.setArguments("{}");
        call.setAssignResultToVar("hits");
        call.setFailOnError(false);
        WorkflowData wf = new WorkflowData();
        wf.setName("tool-call-test");
        wf.setResultFrom("hits");
        wf.addSteps(call);

        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(wf, Map.of());

        assertThat(result.isSuccess()).as(String.valueOf(result.getError())).isTrue();
        assertThat(result.getResult()).isEqualTo("Error: inspect me downstream");
    }

    @Test
    void failsDescriptivelyWhenNoToolRuntimeIsAvailable() {
        WorkflowResult result = new WorkflowExecutorService(SpiWorkflowContextFactory.create())
                .executeWorkflow(workflow("{}"), Map.of("topic", "unused"));

        assertThat(result.isError()).isTrue();
        assertThat(String.valueOf(result.getError())).contains("ToolInvoker");
    }
}
