package ai.mindconnect.workflow.test;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Integration tests exercising JavaScript, BeanShell, and Jython steps
 * together in multi-step workflows, including cross-language data passing.
 */
public class CodeStepIntegrationTest {

    private WorkflowExecutorService service() {
        return WorkflowTestHelper.programmaticService();
    }

    // -----------------------------------------------------------------------
    // JavaScript
    // -----------------------------------------------------------------------

    @Test
    public void javaScriptParsesJsonAndExtractsField() {
        WorkflowExecutorService svc = service();

        AssignVariablesData setup = new AssignVariablesData();
        setup.setName("setup");
        setup.getVariableAssignments()
                .add(new VariableAssignment("payload", "{\"score\":99,\"label\":\"A\"}"));

        CodeData parse = new CodeData();
        parse.setName("parse");
        parse.setLanguage("javascript");
        parse.setWrapInFunction(false);
        parse.setCode("JSON.parse(payload).score");
        parse.setAssignResultToVar("score");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_js_parse");
        wf.addSteps(setup, parse);
        wf.setResultFrom("score");

        WorkflowResult result = svc.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(99);
    }

    @Test
    public void javaScriptBuildsObjectResult() {
        WorkflowExecutorService svc = service();

        CodeData build = new CodeData();
        build.setName("build");
        build.setLanguage("javascript");
        build.setWrapInFunction(false);
        build.setCode("JSON.stringify({name: username, doubled: value * 2})");
        build.setAssignResultToVar("json");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_js_build");
        wf.addSteps(build);
        wf.setResultFrom("json");

        WorkflowResult result = svc.executeWorkflow(wf,
                Map.of("username", "alice", "value", 21));

        Assertions.assertThat(result.isSuccess()).isTrue();
        String json = result.getResult().toString();
        Assertions.assertThat(json).contains("alice").contains("42");
    }

    // -----------------------------------------------------------------------
    // BeanShell
    // -----------------------------------------------------------------------

    @Test
    public void beanShellCallsJavaApiDirectly() {
        WorkflowExecutorService svc = service();

        CodeData step = new CodeData();
        step.setName("bsh");
        step.setLanguage("beanshell");
        step.setCode(
                "import java.util.ArrayList;\n" +
                "ArrayList result = new ArrayList();\n" +
                "for (int i = 1; i <= count; i++) { result.add(i * i); }\n" +
                "return result;");
        step.setAssignResultToVar("squares");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_bsh_squares");
        wf.addSteps(step);
        wf.setResultFrom("squares");

        WorkflowResult result = svc.executeWorkflow(wf, Map.of("count", 4));

        Assertions.assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        java.util.List<Object> squares = (java.util.List<Object>) result.getResult();
        Assertions.assertThat(squares).hasSize(4);
        Assertions.assertThat(((Number) squares.get(3)).intValue()).isEqualTo(16); // 4²
    }

    @Test
    public void beanShellConditionalBusinessRule() {
        WorkflowExecutorService svc = service();

        CodeData rule = new CodeData();
        rule.setName("rule");
        rule.setLanguage("beanshell");
        rule.setCode(
                "if (amount > 1000) return \"premium\";\n" +
                "if (amount > 100)  return \"standard\";\n" +
                "return \"basic\";");
        rule.setAssignResultToVar("tier");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_bsh_rule");
        wf.addSteps(rule);
        wf.setResultFrom("tier");

        WorkflowResult r1 = svc.executeWorkflow(wf, Map.of("amount", 1500));
        Assertions.assertThat(r1.getResult()).isEqualTo("premium");

        WorkflowResult r2 = svc.executeWorkflow(wf, Map.of("amount", 500));
        Assertions.assertThat(r2.getResult()).isEqualTo("standard");

        WorkflowResult r3 = svc.executeWorkflow(wf, Map.of("amount", 50));
        Assertions.assertThat(r3.getResult()).isEqualTo("basic");
    }

    // -----------------------------------------------------------------------
    // Jython
    // -----------------------------------------------------------------------

    @Test
    public void jythonFiltersList() {
        WorkflowExecutorService svc = service();

        CodeData step = new CodeData();
        step.setName("filter");
        step.setLanguage("python");
        step.setCode("filter(lambda x: x % 2 == 0, numbers)");
        step.setAssignResultToVar("evens");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_py_filter");
        wf.addSteps(step);
        wf.setResultFrom("evens");

        WorkflowResult result = svc.executeWorkflow(wf,
                Map.of("numbers", List.of(1, 2, 3, 4, 5, 6)));

        Assertions.assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Object> evens = (List<Object>) result.getResult();
        Assertions.assertThat(evens).hasSize(3);
    }

    @Test
    public void jythonStringManipulation() {
        WorkflowExecutorService svc = service();

        CodeData step = new CodeData();
        step.setName("transform");
        step.setLanguage("python");
        step.setCode("', '.join([w.capitalize() for w in sentence.split()])");
        step.setAssignResultToVar("capitalized");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_py_string");
        wf.addSteps(step);
        wf.setResultFrom("capitalized");

        WorkflowResult result = svc.executeWorkflow(wf,
                Map.of("sentence", "hello world from jython"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString())
                .isEqualTo("Hello, World, From, Jython");
    }

    // -----------------------------------------------------------------------
    // Cross-language workflows
    // -----------------------------------------------------------------------

    @Test
    public void beanShellComputesThenJavaScriptFormats() {
        WorkflowExecutorService svc = service();

        // Step 1: BeanShell computes sum and count separately
        CodeData compute = new CodeData();
        compute.setName("compute");
        compute.setLanguage("beanshell");
        compute.setCode(
                "int total = 0;\n" +
                "int n = 0;\n" +
                "for (int v : values) { total += v; n++; }\n" +
                "count = n;\n" +  // export count to scope
                "total");         // last expression = result
        compute.setAssignResultToVar("total");
        compute.setExportVariables(true);

        // Step 2: JavaScript formats both values as JSON
        CodeData format = new CodeData();
        format.setName("format");
        format.setLanguage("javascript");
        format.setWrapInFunction(false);
        format.setCode("JSON.stringify({sum: total, count: count})");
        format.setAssignResultToVar("report");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_bsh_js");
        wf.addSteps(compute, format);
        wf.setResultFrom("report");

        WorkflowResult result = svc.executeWorkflow(wf,
                Map.of("values", List.of(10, 20, 30)));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString())
                .contains("\"sum\":60")
                .contains("\"count\":3");
    }

    @Test
    public void jythonPreparesDataThenBeanShellProcesses() {
        WorkflowExecutorService svc = service();

        // Step 1: Jython builds a comma-separated string from a list
        CodeData prepare = new CodeData();
        prepare.setName("prepare");
        prepare.setLanguage("python");
        prepare.setCode("','.join([str(x) for x in items])");
        prepare.setAssignResultToVar("csv");

        // Step 2: BeanShell splits and sums
        CodeData process = new CodeData();
        process.setName("process");
        process.setLanguage("beanshell");
        process.setCode(
                "String[] parts = csv.split(\",\");\n" +
                "int sum = 0;\n" +
                "for (String p : parts) { sum += Integer.parseInt(p.trim()); }\n" +
                "return sum;");
        process.setAssignResultToVar("total");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_py_bsh");
        wf.addSteps(prepare, process);
        wf.setResultFrom("total");

        WorkflowResult result = svc.executeWorkflow(wf,
                Map.of("items", List.of(5, 10, 15)));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(((Number) result.getResult()).intValue()).isEqualTo(30);
    }

    @Test
    public void allThreeLanguagesInSequence() {
        WorkflowExecutorService svc = service();

        // JavaScript: compute initial value
        CodeData js = new CodeData();
        js.setName("js");
        js.setLanguage("javascript");
        js.setWrapInFunction(false);
        js.setCode("base * 2");
        js.setAssignResultToVar("doubled");

        // BeanShell: add a constant
        CodeData bsh = new CodeData();
        bsh.setName("bsh");
        bsh.setLanguage("beanshell");
        bsh.setCode("return doubled + 10;");
        bsh.setAssignResultToVar("added");

        // Jython: convert to string with label
        CodeData py = new CodeData();
        py.setName("py");
        py.setLanguage("python");
        py.setCode("'Result=' + str(added)");
        py.setAssignResultToVar("final");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_all_langs");
        wf.addSteps(js, bsh, py);
        wf.setResultFrom("final");

        // base=5 → doubled=10 → added=20 → final="Result=20"
        WorkflowResult result = svc.executeWorkflow(wf, Map.of("base", 5));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).isEqualTo("Result=20");
    }
}
