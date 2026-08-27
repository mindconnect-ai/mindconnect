package ai.mindconnect.workflow.code.groovy;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.*;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Integration tests for Groovy execution via the shared {@link ai.mindconnect.workflow.code.ScriptExecutor}.
 * Uses {@link CodeData} with {@code language="groovy"} — no language-specific subclass.
 */
public class GroovyStepTest {

    private WorkflowContextFactory buildFactory() {
        WorkflowContextFactory ctx = new DefaultWorkflowContextFactory();
        new GroovyWorkflowConfigurer().configure(ctx);
        return ctx;
    }

    private WorkflowResult run(String code, Map<String, Object> params) {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());
        CodeData step = new CodeData();
        step.setName("groovy");
        step.setLanguage("groovy");
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
        WorkflowResult result = run("name.toUpperCase()", Map.of("name", "groovy"));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("GROOVY");
    }

    @Test
    public void lastExpressionIsReturnValue() {
        WorkflowResult result = run("def x = a + b\nx * 2", Map.of("a", 3, "b", 7));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(20);
    }

    @Test
    public void explicitReturnAlsoWorks() {
        WorkflowResult result = run("return price * quantity", Map.of("price", 12, "quantity", 5));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(60);
    }

    @Test
    public void groovyClosureAndCollections() {
        WorkflowResult result = run("[1, 2, 3, 4, 5].findAll { it > 2 }.size()", Map.of());
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(3);
    }

    @Test
    public void exportedVariableVisibleInNextStep() {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());

        CodeData write = new CodeData();
        write.setName("write");
        write.setLanguage("groovy");
        write.setCode("computed = base * 3");
        write.setExportVariables(true);

        CodeData read = new CodeData();
        read.setName("read");
        read.setLanguage("groovy");
        read.setCode("computed + 1");
        read.setAssignResultToVar("answer");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_export");
        wf.addSteps(write, read);
        wf.setResultFrom("answer");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("base", 10));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(31);
    }

    @Test
    public void syntaxErrorWrappedInCodeStepException() {
        WorkflowResult result = run("if (", Map.of());
        Assertions.assertThat(result.isError()).isTrue();
        StepExecutionException stepEx = (StepExecutionException) result.getError();
        Assertions.assertThat(stepEx.getCausingException()).isInstanceOf(CodeStepException.class);
    }

    @Test
    public void configurerRegistersEngine() {
        WorkflowContextFactory ctx = buildFactory();
        ScriptExecutor executor = ctx.getScriptExecutor();
        Assertions.assertThat(executor.getRegisteredLanguages()).contains("groovy");
    }
}
