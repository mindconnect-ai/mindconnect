package ai.mindconnect.workflow.code.javascript;

import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.*;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Integration tests for JavaScript execution via the shared {@link ai.mindconnect.workflow.code.ScriptExecutor}.
 * Uses {@link CodeData} with {@code language="javascript"} — no language-specific subclass.
 */
public class JavaScriptStepTest {

    private WorkflowContextFactory buildFactory() {
        WorkflowContextFactory ctx = new DefaultWorkflowContextFactory();
        new JavaScriptWorkflowConfigurer().configure(ctx);         // registers GraalJS engine
        return ctx;
    }

    private WorkflowResult run(String code, Map<String, Object> params) {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());
        CodeData step = new CodeData();
        step.setName("js");
        step.setLanguage("javascript");
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
        WorkflowResult result = run("name.toUpperCase()", Map.of("name", "graal"));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("GRAAL");
    }

    @Test
    public void multiLineScript() {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());
        CodeData step = new CodeData();
        step.setName("js");
        step.setLanguage("javascript");
        step.setWrapInFunction(false);
        step.setCode("var x = a + b; x * 2");
        step.setAssignResultToVar("result");
        WorkflowData wf = new WorkflowData();
        wf.setName("wf");
        wf.addSteps(step);
        wf.setResultFrom("result");
        WorkflowResult result = service.executeWorkflow(wf, Map.of("a", 3, "b", 7));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(20);
    }

    @Test
    public void arrowFunctionAndArrayFilter() {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());
        CodeData step = new CodeData();
        step.setName("js");
        step.setLanguage("javascript");
        step.setWrapInFunction(false);
        step.setCode("var arr = [a, b, c, d]; arr.filter(x => x > 2).length");
        step.setAssignResultToVar("result");
        WorkflowData wf = new WorkflowData();
        wf.setName("wf");
        wf.addSteps(step);
        wf.setResultFrom("result");
        WorkflowResult result = service.executeWorkflow(wf, Map.of("a", 1, "b", 2, "c", 3, "d", 4));
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(2);
    }

    @Test
    public void syntaxErrorWrappedInCodeStepException() {
        WorkflowResult result = run("if (", Map.of());
        Assertions.assertThat(result.isError()).isTrue();
        StepExecutionException stepEx = (StepExecutionException) result.getError();
        Assertions.assertThat(stepEx.getCausingException()).isInstanceOf(CodeStepException.class);
    }

    @Test
    public void exportedVariableVisibleInNextStep() {
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());

        CodeData write = new CodeData();
        write.setName("write");
        write.setLanguage("javascript");
        write.setCode("computed = 42");
        write.setExportVariables(true);

        CodeData read = new CodeData();
        read.setName("read");
        read.setLanguage("javascript");
        read.setCode("computed + 1");
        read.setAssignResultToVar("answer");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_export");
        wf.addSteps(write, read);
        wf.setResultFrom("answer");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());
        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(43);
    }

    @Test
    public void plainJsObjectsFlowThroughForEachAndBackIntoJs() {
        // The pattern data-shaping workflows rely on, with no Java interop:
        // a JS step builds an array of objects (bare assignment — `var` stays
        // local to the script and is not exported), ForEach iterates it, a JS
        // step inside the loop reads the object's properties, and the loop
        // itself collects and joins the per-iteration results.
        WorkflowExecutorService service = new WorkflowExecutorService(buildFactory());

        CodeData build = new CodeData();
        build.setName("build");
        build.setLanguage("javascript");
        build.setWrapInFunction(false);
        build.setCode("sections = [{title: 'A', content: 'one'}, {title: 'B', content: 'two'}];");
        build.setExportVariables(true);

        CodeData collect = new CodeData();
        collect.setName("collect");
        collect.setLanguage("javascript");
        collect.setWrapInFunction(false);
        collect.setCode("section.title + ':' + section.content");
        collect.setAssignResultToVar("line");

        ForEachData each = new ForEachData();
        each.setName("each");
        each.setLoopOver("sections");
        each.setRunVar("section");
        each.setResultFrom("line");
        each.setAssignResultToVar("joined");
        each.setJoinResults(true);
        each.setJoinDelimiter("|");
        each.addSteps(collect);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_objects");
        wf.addSteps(build, each);
        wf.setResultFrom("joined");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());
        Assertions.assertThat(result.isSuccess()).as(String.valueOf(result.getError())).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo("A:one|B:two");
    }

    @Test
    public void configurerRegistersEngine() {
        WorkflowContextFactory ctx = buildFactory();
        ScriptExecutor executor = ctx.getScriptExecutor();
        Assertions.assertThat(executor.getRegisteredLanguages()).contains("javascript", "js", "graal.js");
    }
}
