package ai.mindconnect.workflow.test;

import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.StepInstanceFactory;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Tests that all step types (including optional modules) survive a full
 * JSON serialize → deserialize → execute round-trip.
 */
public class JsonSerializationIntegrationTest {

    private JacksonWorkflowSerializer serializer() {
        return WorkflowTestHelper.serializer();
    }

    private WorkflowExecutorService service() {
        return WorkflowTestHelper.programmaticService();
    }

    private WorkflowData roundTrip(WorkflowData original) throws Exception {
        JacksonWorkflowSerializer s = serializer();
        String json = s.write(original);
        return s.read(json);
    }

    // -----------------------------------------------------------------------
    // Round-trip tests
    // -----------------------------------------------------------------------

    @Test
    public void httpCallDataRoundTrip() throws Exception {
        HttpCallData step = new HttpCallData();
        step.setName("fetch");
        step.setUrl("https://example.com/api/${id}");
        step.setMethod("POST");
        step.setBody("{\"key\":\"${value}\"}");
        step.setFailOnError(false);
        step.setStatusCodeVar("status");
        step.getHeaders().add(new VariableAssignment("Authorization", "Bearer ${token}"));

        WorkflowData original = new WorkflowData();
        original.setName("wf_http_rt");
        original.addSteps(step);

        WorkflowData restored = roundTrip(original);

        HttpCallData restored_step = (HttpCallData) restored.getSteps().get(0);
        Assertions.assertThat(restored_step.getUrl()).isEqualTo("https://example.com/api/${id}");
        Assertions.assertThat(restored_step.getMethod()).isEqualTo("POST");
        Assertions.assertThat(restored_step.getBody()).isEqualTo("{\"key\":\"${value}\"}");
        Assertions.assertThat(restored_step.isFailOnError()).isFalse();
        Assertions.assertThat(restored_step.getStatusCodeVar()).isEqualTo("status");
        Assertions.assertThat(restored_step.getHeaders()).hasSize(1);
        Assertions.assertThat(restored_step.getHeaders().get(0).getVarName())
                .isEqualTo("Authorization");
    }

    @Test
    public void javaScriptDataRoundTrip() throws Exception {
        CodeData step = new CodeData();
        step.setName("js");
        step.setLanguage("javascript");
        step.setCode("x + y");
        step.setWrapInFunction(false);
        step.setAssignResultToVar("sum");

        WorkflowData original = new WorkflowData();
        original.setName("wf_js_rt");
        original.addSteps(step);

        WorkflowData restored = roundTrip(original);

        CodeData r = (CodeData) restored.getSteps().get(0);
        Assertions.assertThat(r.getCode()).isEqualTo("x + y");
        Assertions.assertThat(r.getLanguage()).isEqualTo("javascript");
        Assertions.assertThat(r.isWrapInFunction()).isFalse();
        Assertions.assertThat(r.getAssignResultToVar()).isEqualTo("sum");
    }

    @Test
    public void beanShellDataRoundTrip() throws Exception {
        CodeData step = new CodeData();
        step.setName("bsh");
        step.setLanguage("beanshell");
        step.setCode("return x * 2;");
        step.setAssignResultToVar("result");

        WorkflowData original = new WorkflowData();
        original.setName("wf_bsh_rt");
        original.addSteps(step);

        WorkflowData restored = roundTrip(original);

        CodeData r = (CodeData) restored.getSteps().get(0);
        Assertions.assertThat(r.getCode()).isEqualTo("return x * 2;");
        Assertions.assertThat(r.getLanguage()).isEqualTo("beanshell");
    }

    @Test
    public void jythonDataRoundTrip() throws Exception {
        CodeData step = new CodeData();
        step.setName("py");
        step.setLanguage("python");
        step.setCode("x * 2");
        step.setAssignResultToVar("result");

        WorkflowData original = new WorkflowData();
        original.setName("wf_py_rt");
        original.addSteps(step);

        WorkflowData restored = roundTrip(original);

        CodeData r = (CodeData) restored.getSteps().get(0);
        Assertions.assertThat(r.getCode()).isEqualTo("x * 2");
        Assertions.assertThat(r.getLanguage()).isEqualTo("python");
    }

    @Test
    public void mixedStepTypesRoundTripAndExecute() throws Exception {
        HttpCallData http = new HttpCallData();
        http.setName("http");
        http.setUrl("https://example.com");
        http.setMethod("GET");

        CodeData js = new CodeData();
        js.setName("js");
        js.setLanguage("javascript");
        js.setCode("x + 1");
        js.setWrapInFunction(false);
        js.setAssignResultToVar("y");

        CodeData bsh = new CodeData();
        bsh.setName("bsh");
        bsh.setLanguage("beanshell");
        bsh.setCode("return y + 1;");
        bsh.setAssignResultToVar("z");

        CodeData py = new CodeData();
        py.setName("py");
        py.setLanguage("python");
        py.setCode("z + 1");
        py.setAssignResultToVar("final");

        WorkflowData original = new WorkflowData();
        original.setName("wf_mixed_rt");
        original.addSteps(http, js, bsh, py);
        original.setResultFrom("final");

        // Serialize and deserialize
        JacksonWorkflowSerializer s = serializer();
        String json = s.write(original);

        // Verify @class discriminators are present in the JSON
        Assertions.assertThat(json)
                .contains("ai.mindconnect.workflow.domain.HttpCallData")
                .contains("ai.mindconnect.workflow.domain.CodeData");

        // Restore and execute the non-HTTP steps (skip HTTP since no server)
        WorkflowData wf = new WorkflowData();
        wf.setName("wf_exec");
        wf.addSteps(js, bsh, py);
        wf.setResultFrom("final");

        WorkflowData restoredExec = s.read(s.write(wf));
        WorkflowResult result = service().executeWorkflow(restoredExec, Map.of("x", 10L));

        Assertions.assertThat(result.isSuccess()).isTrue();
        // x=10 → js: y=11 → bsh: z=12 → py: final=13
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(13);
    }

    @Test
    public void complexWorkflowFromClasspath() throws Exception {
        WorkflowExecutorService classpathService = WorkflowTestHelper.classpathService();
        // Verify the classpath-loaded workflow JSON parses and its step types are resolved
        JacksonWorkflowSerializer s = WorkflowTestHelper.serializer();
        WorkflowData wf = s.readFromClasspath("/workflows/wf_http_and_code.json");

        Assertions.assertThat(wf.getName()).isEqualTo("wf_http_and_code");
        Assertions.assertThat(wf.getSteps()).hasSize(2);
        Assertions.assertThat(wf.getSteps().get(0)).isInstanceOf(HttpCallData.class);
        Assertions.assertThat(wf.getSteps().get(1)).isInstanceOf(CodeData.class);
        // StepInstanceFactory can instantiate both without throwing
        StepInstanceFactory factory = WorkflowTestHelper.fullFactory().getStepInstanceFactory();
        wf.getSteps().forEach(step ->
                Assertions.assertThatCode(() -> factory.instantiate(step))
                        .doesNotThrowAnyException());
    }
}
