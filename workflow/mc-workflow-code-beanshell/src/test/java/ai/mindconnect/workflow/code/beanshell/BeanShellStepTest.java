package ai.mindconnect.workflow.code.beanshell;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.*;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Integration tests for BeanShell execution via the shared {@link ai.mindconnect.workflow.code.ScriptExecutor}.
 * Uses {@link CodeData} with {@code language="beanshell"} — no language-specific subclass.
 */
public class BeanShellStepTest {

    private WorkflowContextFactory buildFactory() {
        WorkflowContextFactory ctx = new DefaultWorkflowContextFactory();
        new BeanShellWorkflowConfigurer().configure(ctx);
        return ctx;
    }

    private WorkflowResult run(String code, Map<String, Object> params) {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());
        CodeData step = new CodeData();
        step.setName("bsh");
        step.setLanguage("beanshell");
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
        WorkflowResult result = run("return 2 + 2;", Map.of());
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(4);
    }

    @Test
    public void accessesInjectedVariable() {
        WorkflowResult result = run("return x * 3;", Map.of("x", 7));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(21);
    }

    @Test
    public void stringConcatenation() {
        WorkflowResult result = run("return \"Hello, \" + name + \"!\";", Map.of("name", "BeanShell"));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("Hello, BeanShell!");
    }

    @Test
    public void javaStandardLibraryAccess() {
        WorkflowResult result = run(
                "import java.util.ArrayList; " +
                "ArrayList list = new ArrayList(); " +
                "list.add(\"a\"); list.add(\"b\"); " +
                "return list.size();",
                Map.of());
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(2);
    }

    @Test
    public void conditionalLogic() {
        WorkflowResult result = run(
                "if (score >= 50) { return \"pass\"; } else { return \"fail\"; }",
                Map.of("score", 75));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("pass");
    }

    @Test
    public void exportedVariableVisibleInNextStep() {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());

        CodeData write = new CodeData();
        write.setName("write");
        write.setLanguage("beanshell");
        write.setCode("computed = 100;");
        write.setExportVariables(true);

        CodeData read = new CodeData();
        read.setName("read");
        read.setLanguage("beanshell");
        read.setCode("return computed + 1;");
        read.setAssignResultToVar("answer");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_export");
        wf.addSteps(write, read);
        wf.setResultFrom("answer");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(101);
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
        Assertions.assertThat(executor.getRegisteredLanguages()).contains("beanshell", "bsh");
    }
}
