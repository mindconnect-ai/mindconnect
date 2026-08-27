package ai.mindconnect.workflow.code.jython;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.*;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Integration tests for Jython execution via the shared {@link ai.mindconnect.workflow.code.ScriptExecutor}.
 * Uses {@link CodeData} with {@code language="python"} — no language-specific subclass.
 */
public class JythonStepTest {

    private WorkflowContextFactory buildFactory() {
        WorkflowContextFactory ctx = new DefaultWorkflowContextFactory();
        new JythonWorkflowConfigurer().configure(ctx);
        return ctx;
    }

    private WorkflowResult run(String code, Map<String, Object> params) {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());
        CodeData step = new CodeData();
        step.setName("py");
        step.setLanguage("python");
        step.setCode(code);
        step.setAssignResultToVar("result");
        WorkflowData wf = new WorkflowData();
        wf.setName("wf");
        wf.addSteps(step);
        wf.setResultFrom("result");
        return service.executeWorkflow(wf, params);
    }

    @Test
    public void simpleArithmetic() {
        WorkflowResult result = run("2 + 2", Map.of());
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(4);
    }

    @Test
    public void accessesInjectedVariable() {
        WorkflowResult result = run("x * 3", Map.of("x", 7));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(21);
    }

    @Test
    public void stringUpperCase() {
        WorkflowResult result = run("name.upper()", Map.of("name", "jython"));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).isEqualTo("JYTHON");
    }

    @Test
    public void listComprehension() {
        WorkflowResult result = run(
                "[x * 2 for x in items]",
                Map.of("items", Arrays.asList(1, 2, 3)));
        Assertions.assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result.getResult();
        Assertions.assertThat(list).hasSize(3);
        Assertions.assertThat(((Number) list.get(0)).intValue()).isEqualTo(2);
        Assertions.assertThat(((Number) list.get(2)).intValue()).isEqualTo(6);
    }

    @Test
    public void multiLineScript() {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());

        CodeData step = new CodeData();
        step.setName("py");
        step.setLanguage("python");
        step.setExportVariables(true);
        step.setCode(
                "total = 0\n" +
                "for i in range(n):\n" +
                "    total += i\n");

        CodeData readBack = new CodeData();
        readBack.setName("readback");
        readBack.setLanguage("python");
        readBack.setCode("total");
        readBack.setAssignResultToVar("answer");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_multi");
        wf.addSteps(step, readBack);
        wf.setResultFrom("answer");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("n", 5));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(10);
    }

    @Test
    public void syntaxErrorWrappedInCodeStepException() {
        WorkflowResult result = run("def (", Map.of());
        Assertions.assertThat(result.isError()).isTrue();
        StepExecutionException stepEx = (StepExecutionException) result.getError();
        Assertions.assertThat(stepEx.getCausingException()).isInstanceOf(CodeStepException.class);
    }

    @Test
    public void configurerRegistersEngine() {
        WorkflowContextFactory ctx = buildFactory();
        ScriptExecutor executor = ctx.getScriptExecutor();
        Assertions.assertThat(executor.getRegisteredLanguages()).contains("python", "jython");
    }
}
