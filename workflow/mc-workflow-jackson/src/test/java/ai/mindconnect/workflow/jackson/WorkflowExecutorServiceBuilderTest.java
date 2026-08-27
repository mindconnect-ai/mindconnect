package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.domain.*;
import ai.mindconnect.workflow.execution.*;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Integration tests for {@link WorkflowExecutorService}.
 *
 * These tests verify the full pipeline: factory setup → executor → execution → result,
 * including workflows built programmatically and loaded from classpath JSON.
 */
public class WorkflowExecutorServiceBuilderTest {

    private WorkflowContextFactory workflowContextFactory;
    private WorkflowExecutorService service;

    @BeforeEach
    public void setup() {
        workflowContextFactory = new DefaultWorkflowContextFactory();
        new JsonWorkflowConfigurer().configure(workflowContextFactory);

        // Wire Jackson-based json mapper and classpath registry
         service = new WorkflowExecutorService(workflowContextFactory);
    }

    // -----------------------------------------------------------------------
    // Factory basics
    // -----------------------------------------------------------------------

    @Test
    public void defaultBuildSucceeds() {
        Assertions.assertThat(service).isNotNull();
        Assertions.assertThat(service.getContextFactory()).isNotNull();
        Assertions.assertThat(service.getContextFactory().getStepInstanceFactory()).isNotNull();
        Assertions.assertThat(service.getContextFactory().getWorkflowDefinitionRegistry()).isNotNull();
        Assertions.assertThat(service.getContextFactory().getJsonMapper()).isNotNull();
    }

    @Test
    public void customObjectMapperIsUsed() {
        // Verify that the context factory holds a JacksonJsonMapper
        Assertions.assertThat(service.getContextFactory().getJsonMapper()).isNotNull();
        Assertions.assertThat(service.getContextFactory().getJsonMapper())
                .isInstanceOf(JacksonJsonMapper.class);
    }

    // -----------------------------------------------------------------------
    // Programmatic workflow execution
    // -----------------------------------------------------------------------

    @Test
    public void executeProgrammaticWorkflow() {
        WorkflowData wd = new WorkflowData();
        wd.setName("wf_prog");
        AssignVariablesData step = new AssignVariablesData();
        step.setName("greet");
        step.getVariableAssignments().add(new VariableAssignment("msg", "Hi ${name}"));
        step.setAssignResultToVar("msg");
        wd.addSteps(step);
        wd.setResultFrom("msg");

        WorkflowResult result = service.executeWorkflow(wd, Map.of("name", "World"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("Hi World");
    }

    // -----------------------------------------------------------------------
    // Classpath workflow loading
    // -----------------------------------------------------------------------

    @Test
    public void loadAndExecuteWorkflowFromClasspath() {
        // Loads src/test/resources/workflows/wf_hello.json
        WorkflowData wd = service.getContextFactory()
                .getWorkflowDefinitionRegistry()
                .getWorkflowDefinition("wf_hello");

        Assertions.assertThat(wd).isNotNull();
        Assertions.assertThat(wd.getName()).isEqualTo("wf_hello");

        WorkflowResult result = service.executeWorkflow(wd, Map.of("name", "Jackson"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("Hello Jackson!");
    }

    @Test
    public void missingClasspathWorkflowReturnsNull() {
        WorkflowData wd = service.getContextFactory()
                .getWorkflowDefinitionRegistry()
                .getWorkflowDefinition("does_not_exist");

        Assertions.assertThat(wd).isNull();
    }

    // -----------------------------------------------------------------------
    // Sub-workflow via registry
    // -----------------------------------------------------------------------

    @Test
    public void callWorkflowStepUsesRegistry() {
        WorkflowData sub = new WorkflowData();
        sub.setName("wf_sub");
        AssignVariablesData subStep = new AssignVariablesData();
        subStep.setName("compute");
        subStep.getVariableAssignments().add(new VariableAssignment("computed", "result_of_${input}"));
        subStep.setAssignResultToVar("computed");
        sub.addSteps(subStep);
        sub.setResultFrom("computed");

        WorkflowData parent = new WorkflowData();
        parent.setName("wf_parent");
        CallWorkflowData call = new CallWorkflowData();
        call.setName("callSub");
        call.setWorkflow("wf_sub");
        call.addAssignParam("input", "${query}");
        call.setAssignResultToVar("sub_result");
        parent.addSteps(call);
        parent.setResultFrom("sub_result");

        service.getContextFactory().getWorkflowDefinitionRegistry()
                .putWorkflowDefinition("wf_sub", sub);

        WorkflowResult result = service.executeWorkflow(parent, Map.of("query", "hello"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("result_of_hello");
    }

    // -----------------------------------------------------------------------
    // JSON serialization round-trip + execution
    // -----------------------------------------------------------------------

    @Test
    public void serializeDeserializeThenExecute() throws Exception {
        // Build a workflow
        WorkflowData original = new WorkflowData();
        original.setName("wf_serde");
        ForEachData forEach = new ForEachData();
        forEach.setName("loop");
        forEach.setRunVar("item");
        forEach.setIndexVar("i");
        forEach.setLoopOver("items");
        forEach.setJoinResults(true);
        forEach.setJoinDelimiter(", ");
        AssignVariablesData inner = new AssignVariablesData();
        inner.setName("assign");
        inner.getVariableAssignments().add(new VariableAssignment("processed", "${item}!"));
        inner.setAssignResultToVar("processed");
        forEach.addSteps(inner);
        forEach.setResultFrom("processed");
        forEach.setAssignResultToVar("joined");
        original.addSteps(forEach);
        original.setResultFrom("joined");

        // Serialize to JSON and back
        var mapper = WorkflowObjectMapperFactory.create();
        var serializer = new JacksonWorkflowSerializer(mapper);
        String json = serializer.write(original);
        WorkflowData restored = serializer.read(json);

        // Execute the deserialized workflow
        WorkflowResult result = service.executeWorkflow(
                restored, Map.of("items", List.of("a", "b", "c")));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("a!, b!, c!");
    }

    // -----------------------------------------------------------------------
    // Event listener wiring
    // -----------------------------------------------------------------------

    @Test
    public void eventListenerReceivesStepEvents() {
        java.util.List<String> log = new java.util.ArrayList<>();
        service.addEventListener(new WorkflowEventListener() {
            @Override public void beforeStepExecute(StepInstance<?> s) {
                log.add("before:" + s.getConfig().getName());
            }
            @Override public void afterStepExecute(StepInstance<?> s) {
                log.add("after:" + s.getConfig().getName());
            }
        });

        WorkflowData wd = new WorkflowData();
        wd.setName("wf_events");
        AssignVariablesData step = new AssignVariablesData();
        step.setName("myStep");
        step.getVariableAssignments().add(new VariableAssignment("x", "1"));
        wd.addSteps(step);

        service.executeWorkflow(wd, Map.of());

        Assertions.assertThat(log).contains("before:myStep", "after:myStep");
    }
}
