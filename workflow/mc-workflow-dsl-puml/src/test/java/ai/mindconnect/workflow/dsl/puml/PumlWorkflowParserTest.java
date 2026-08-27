package ai.mindconnect.workflow.dsl.puml;

import ai.mindconnect.workflow.domain.*;
import ai.mindconnect.workflow.domain.HttpCallData;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PumlWorkflowParser}.
 *
 * Each test loads its workflow definition from a {@code .puml} file under
 * {@code src/test/resources/puml/} via the classpath, keeping the test class
 * free of inline DSL strings.
 *
 * Tests cover all built-in step types, conditional branching, foreach loops,
 * metadata directives, sub-workflow definitions, and the serializer round-trip.
 */
public class PumlWorkflowParserTest {

    private final PumlWorkflowParser parser = new PumlWorkflowParser();
    private final PumlWorkflowSerializer serializer = new PumlWorkflowSerializer();

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private WorkflowData load(String fileName) {
        String path = "puml/" + fileName;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Test resource not found: " + path);
            return parser.parse(in);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load test resource: " + path, e);
        }
    }

    // -----------------------------------------------------------------------
    // Basic parsing
    // -----------------------------------------------------------------------

    @Test
    public void parsesWorkflowName() {
        WorkflowData wf = load("wf_name.puml");
        assertThat(wf.getName()).isEqualTo("myWorkflow");
    }

    @Test
    public void parsesParamsAndResultFrom() {
        WorkflowData wf = load("wf_meta.puml");
        assertThat(wf.paramNames()).containsExactly("userId", "amount");
        assertThat(wf.getResultFrom()).isEqualTo("total");
    }

    @Test
    public void missingStartUmlThrows() {
        assertThatThrownBy(() -> parser.parse("not a plantuml file"))
                .isInstanceOf(PumlParseException.class);
    }

    // -----------------------------------------------------------------------
    // Assign step
    // -----------------------------------------------------------------------

    @Test
    public void parsesAssignStep() {
        WorkflowData wf = load("wf_assign.puml");
        assertThat(wf.getSteps()).hasSize(1);

        AssignVariablesData assign = (AssignVariablesData) wf.getSteps().get(0);
        assertThat(assign.getName()).isEqualTo("greet");
        assertThat(assign.getVariableAssignments()).hasSize(1);
        assertThat(assign.getVariableAssignments().get(0).getVarName()).isEqualTo("message");
        assertThat(assign.getVariableAssignments().get(0).getExpressionOrVarName()).isEqualTo("Hello World");
        assertThat(assign.getAssignResultToVar()).isEqualTo("message");
    }

    @Test
    public void parsesMultipleAssignments() {
        WorkflowData wf = load("wf_multi_assign.puml");
        AssignVariablesData assign = (AssignVariablesData) wf.getSteps().get(0);
        assertThat(assign.getVariableAssignments()).hasSize(3);
    }

    @Test
    public void parsesVariableExpression() {
        WorkflowData wf = load("wf_expr.puml");
        AssignVariablesData assign = (AssignVariablesData) wf.getSteps().get(0);
        assertThat(assign.getVariableAssignments().get(0).getExpressionOrVarName()).isEqualTo("${in}");
    }

    // -----------------------------------------------------------------------
    // Halt step
    // -----------------------------------------------------------------------

    @Test
    public void parsesHaltStep() {
        WorkflowData wf = load("wf_halt.puml");
        HaltData halt = (HaltData) wf.getSteps().get(0);
        assertThat(halt.getName()).isEqualTo("waitStep");
        assertThat(halt.getNext()).isEqualTo("continueStep");
    }

    @Test
    public void parsesHaltWithCondition() {
        WorkflowData wf = load("wf_halt_cond.puml");
        HaltData halt = (HaltData) wf.getSteps().get(0);
        assertThat(halt.getCondition()).isEqualTo("${needsApproval}");
        assertThat(halt.getNext()).isEqualTo("afterApproval");
    }

    // -----------------------------------------------------------------------
    // Jump step
    // -----------------------------------------------------------------------

    @Test
    public void parsesJumpStep() {
        WorkflowData wf = load("wf_jump.puml");
        JumpToData jump = (JumpToData) wf.getSteps().get(0);
        assertThat(jump.getName()).isEqualTo("skipToEnd");
        assertThat(jump.getJumpTo()).isEqualTo("endStep");
    }

    // -----------------------------------------------------------------------
    // Call workflow step
    // -----------------------------------------------------------------------

    @Test
    public void parsesCallWorkflowStep() {
        WorkflowData wf = load("wf_call.puml");
        CallWorkflowData call = (CallWorkflowData) wf.getSteps().get(0);
        assertThat(call.getName()).isEqualTo("invokeSubWf");
        assertThat(call.getWorkflow()).isEqualTo("subWorkflow");
        assertThat(call.getAssignParams()).containsEntry("userId", "${currentUser}");
        assertThat(call.getAssignResultToVar()).isEqualTo("subResult");
    }

    // -----------------------------------------------------------------------
    // If/then/else
    // -----------------------------------------------------------------------

    @Test
    public void parsesIfWithElse() {
        WorkflowData wf = load("wf_if.puml");
        assertThat(wf.getSteps()).hasSize(1);

        IfData ifData = (IfData) wf.getSteps().get(0);
        assertThat(ifData.getConditions()).hasSize(1);
        assertThat(ifData.getConditions()[0].getCondition()).isEqualTo("${flag}");
        assertThat(ifData.getConditions()[0].getThenBlock()).isNotNull();
        assertThat(ifData.getConditions()[0].getThenBlock().getSteps()).hasSize(1);
        assertThat(ifData.getElseBlock()).isNotNull();
        assertThat(ifData.getElseBlock().getSteps()).hasSize(1);
    }

    @Test
    public void parsesIfWithoutElse() {
        WorkflowData wf = load("wf_if_no_else.puml");
        IfData ifData = (IfData) wf.getSteps().get(0);
        assertThat(ifData.getElseBlock()).isNull();
    }

    @Test
    public void parsesSpelConditionInIf() {
        WorkflowData wf = load("wf_spel_if.puml");
        IfData ifData = (IfData) wf.getSteps().get(0);
        assertThat(ifData.getConditions()[0].getCondition()).isEqualTo("spel: score >= 90");
        assertThat(wf.getResultFrom()).isEqualTo("tier");
    }

    // -----------------------------------------------------------------------
    // ForEach
    // -----------------------------------------------------------------------

    @Test
    public void parsesForEach() {
        WorkflowData wf = load("wf_foreach.puml");
        ForEachData forEach = (ForEachData) wf.getSteps().get(0);
        assertThat(forEach.getLoopOver()).isEqualTo("items");
        assertThat(forEach.getRunVar()).isEqualTo("item");
        assertThat(forEach.getIndexVar()).isEqualTo("i");
        assertThat(forEach.isParallel()).isTrue();
        assertThat(forEach.getSteps()).hasSize(2);
        assertThat(forEach.getAssignResultToVar()).isEqualTo("results");
    }

    // -----------------------------------------------------------------------
    // Sub-workflows
    // -----------------------------------------------------------------------

    @Test
    public void parsesSubWorkflow() {
        WorkflowData wf = load("wf_sub.puml");
        assertThat(wf.getSubWorkflows()).hasSize(1);
        WorkflowData sub = wf.getSubWorkflows().get(0);
        assertThat(sub.getName()).isEqualTo("childWf");
        assertThat(sub.getSteps()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Comments and blank lines
    // -----------------------------------------------------------------------

    @Test
    public void ignoresComments() {
        WorkflowData wf = load("wf_comments.puml");
        assertThat(wf.getSteps()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Multiple steps
    // -----------------------------------------------------------------------

    @Test
    public void parsesMultipleStepsInOrder() {
        WorkflowData wf = load("wf_multi.puml");
        assertThat(wf.getSteps()).hasSize(3);
        assertThat(wf.getSteps().get(0).getName()).isEqualTo("step1");
        assertThat(wf.getSteps().get(1).getName()).isEqualTo("step2");
        assertThat(wf.getSteps().get(2).getName()).isEqualTo("step3");
    }

    // -----------------------------------------------------------------------
    // Serializer round-trip
    // -----------------------------------------------------------------------

    @Test
    public void serializerRoundTripAssign() {
        WorkflowData wf = new WorkflowData();
        wf.setName("wf_rt");
        wf.declareParams("a", "b");
        wf.setResultFrom("out");

        AssignVariablesData assign = new AssignVariablesData();
        assign.setName("compute");
        assign.getVariableAssignments().add(new VariableAssignment("out", "${a}"));
        assign.setAssignResultToVar("out");
        wf.addSteps(assign);

        String puml = serializer.serialize(wf);
        assertThat(puml).contains("@startuml wf_rt");
        assertThat(puml).contains(":input -> a, b;");
        assertThat(puml).contains(":output <- out;");
        assertThat(puml).contains(":assign compute");
        assertThat(puml).contains("out = ${a}");

        WorkflowData restored = parser.parse(puml);
        assertThat(restored.getName()).isEqualTo("wf_rt");
        assertThat(restored.paramNames()).containsExactly("a", "b");
        assertThat(restored.getResultFrom()).isEqualTo("out");
        assertThat(restored.getSteps()).hasSize(1);
        AssignVariablesData restoredAssign = (AssignVariablesData) restored.getSteps().get(0);
        assertThat(restoredAssign.getName()).isEqualTo("compute");
        assertThat(restoredAssign.getVariableAssignments().get(0).getVarName()).isEqualTo("out");
    }

    @Test
    public void serializerRoundTripIf() {
        AssignVariablesData highAssign = new AssignVariablesData();
        highAssign.setName("high");
        highAssign.getVariableAssignments().add(new VariableAssignment("tier", "gold"));

        BlockData thenBlock = new BlockData();
        thenBlock.setName("thenBlock");
        thenBlock.addSteps(highAssign);

        AssignVariablesData lowAssign = new AssignVariablesData();
        lowAssign.setName("low");
        lowAssign.getVariableAssignments().add(new VariableAssignment("tier", "silver"));

        BlockData elseBlock = new BlockData();
        elseBlock.setName("elseBlock");
        elseBlock.addSteps(lowAssign);

        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("spel: score >= 90");
        cond.setThenBlock(thenBlock);

        IfData ifData = new IfData();
        ifData.setName("check");
        ifData.setConditions(cond);
        ifData.setElseBlock(elseBlock);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_if_rt");
        wf.addSteps(ifData);
        wf.setResultFrom("tier");

        String puml = serializer.serialize(wf);
        assertThat(puml).contains("if (spel: score >= 90)");
        assertThat(puml).contains("else");
        assertThat(puml).contains("endif");

        WorkflowData restored = parser.parse(puml);
        IfData restoredIf = (IfData) restored.getSteps().get(0);
        assertThat(restoredIf.getConditions()[0].getCondition()).isEqualTo("spel: score >= 90");
        assertThat(restoredIf.getConditions()[0].getThenBlock().getSteps()).hasSize(1);
        assertThat(restoredIf.getElseBlock().getSteps()).hasSize(1);
    }

    @Test
    public void serializerRoundTripHalt() {
        HaltData halt = new HaltData();
        halt.setName("pause");
        halt.setNext("resume");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_halt_rt");
        wf.addSteps(halt);

        String puml = serializer.serialize(wf);
        WorkflowData restored = parser.parse(puml);

        HaltData restoredHalt = (HaltData) restored.getSteps().get(0);
        assertThat(restoredHalt.getName()).isEqualTo("pause");
        assertThat(restoredHalt.getNext()).isEqualTo("resume");
    }

    @Test
    public void serializerRoundTripForEach() {
        AssignVariablesData innerAssign = new AssignVariablesData();
        innerAssign.setName("process");
        innerAssign.getVariableAssignments().add(new VariableAssignment("result", "${item}"));
        innerAssign.setAssignResultToVar("result");

        ForEachData forEach = new ForEachData();
        forEach.setName("loop");
        forEach.setLoopOver("items");
        forEach.setRunVar("item");
        forEach.setIndexVar("idx");
        forEach.setParallel(true);
        forEach.setAssignResultToVar("loopResults");
        forEach.addSteps(innerAssign);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_foreach_rt");
        wf.addSteps(forEach);

        String puml = serializer.serialize(wf);
        assertThat(puml).contains(":for -> items as item index idx parallel;");
        assertThat(puml).contains("fork");
        assertThat(puml).contains("end fork");
        assertThat(puml).contains(":output <- loopResults;");

        WorkflowData restored = parser.parse(puml);
        ForEachData restoredForEach = (ForEachData) restored.getSteps().get(0);
        assertThat(restoredForEach.getLoopOver()).isEqualTo("items");
        assertThat(restoredForEach.getRunVar()).isEqualTo("item");
        assertThat(restoredForEach.isParallel()).isTrue();
        assertThat(restoredForEach.getAssignResultToVar()).isEqualTo("loopResults");
    }

    // -----------------------------------------------------------------------
    // JsonSerializationIntegrationTest workflows
    // -----------------------------------------------------------------------

    @Test
    public void parsesHttpRoundTripWorkflow() {
        WorkflowData wf = load("wf_http_rt.puml");
        assertThat(wf.getName()).isEqualTo("wf_http_rt");
        assertThat(wf.getSteps()).hasSize(1);

        HttpCallData http = (HttpCallData) wf.getSteps().get(0);
        assertThat(http.getName()).isEqualTo("fetch");
        assertThat(http.getUrl()).isEqualTo("https://example.com/api/${id}");
        assertThat(http.getMethod()).isEqualTo("POST");
        assertThat(http.getBody()).isEqualTo("{\"key\":\"${value}\"}");
        assertThat(http.isFailOnError()).isFalse();
        assertThat(http.getStatusCodeVar()).isEqualTo("status");
        assertThat(http.getHeaders()).hasSize(1);
        assertThat(http.getHeaders().get(0).getVarName()).isEqualTo("Authorization");
        assertThat(http.getHeaders().get(0).getExpressionOrVarName()).isEqualTo("Bearer ${token}");
        assertThat(http.getAssignResultToVar()).isEqualTo("fetch");
    }

    @Test
    public void parsesJavaScriptRoundTripWorkflow() {
        WorkflowData wf = load("wf_js_rt.puml");
        assertThat(wf.getName()).isEqualTo("wf_js_rt");
        assertThat(wf.getSteps()).hasSize(1);

        CodeData code = (CodeData) wf.getSteps().get(0);
        assertThat(code.getName()).isEqualTo("js");
        assertThat(code.getLanguage()).isEqualTo("javascript");
        assertThat(code.getCode()).isEqualTo("x + y");
        assertThat(code.getAssignResultToVar()).isEqualTo("sum");
    }

    @Test
    public void parsesBeanShellRoundTripWorkflow() {
        WorkflowData wf = load("wf_bsh_rt.puml");
        assertThat(wf.getName()).isEqualTo("wf_bsh_rt");
        assertThat(wf.getSteps()).hasSize(1);

        CodeData code = (CodeData) wf.getSteps().get(0);
        assertThat(code.getName()).isEqualTo("bsh");
        assertThat(code.getLanguage()).isEqualTo("beanshell");
        assertThat(code.getCode()).isEqualTo("return x * 2;");
        assertThat(code.getAssignResultToVar()).isEqualTo("result");
    }

    @Test
    public void parsesPythonRoundTripWorkflow() {
        WorkflowData wf = load("wf_py_rt.puml");
        assertThat(wf.getName()).isEqualTo("wf_py_rt");
        assertThat(wf.getSteps()).hasSize(1);

        CodeData code = (CodeData) wf.getSteps().get(0);
        assertThat(code.getName()).isEqualTo("py");
        assertThat(code.getLanguage()).isEqualTo("python");
        assertThat(code.getCode()).isEqualTo("x * 2");
        assertThat(code.getAssignResultToVar()).isEqualTo("result");
    }

    @Test
    public void parsesMixedStepTypesWorkflow() {
        WorkflowData wf = load("wf_mixed_rt.puml");
        assertThat(wf.getName()).isEqualTo("wf_mixed_rt");
        assertThat(wf.paramNames()).containsExactly("x");
        assertThat(wf.getResultFrom()).isEqualTo("final");
        assertThat(wf.getSteps()).hasSize(4);

        assertThat(wf.getSteps().get(0)).isInstanceOf(HttpCallData.class);
        HttpCallData http = (HttpCallData) wf.getSteps().get(0);
        assertThat(http.getName()).isEqualTo("http");
        assertThat(http.getUrl()).isEqualTo("https://www.google.com/search?q=plantuml");
        assertThat(http.getMethod()).isEqualTo("GET");

        CodeData js = (CodeData) wf.getSteps().get(1);
        assertThat(js.getName()).isEqualTo("js");
        assertThat(js.getLanguage()).isEqualTo("javascript");
        assertThat(js.getCode()).isEqualTo("x + 1");
        assertThat(js.getAssignResultToVar()).isEqualTo("y");

        CodeData bsh = (CodeData) wf.getSteps().get(2);
        assertThat(bsh.getName()).isEqualTo("bsh");
        assertThat(bsh.getLanguage()).isEqualTo("beanshell");
        assertThat(bsh.getCode()).isEqualTo("return y + 1;");
        assertThat(bsh.getAssignResultToVar()).isEqualTo("z");

        CodeData py = (CodeData) wf.getSteps().get(3);
        assertThat(py.getName()).isEqualTo("py");
        assertThat(py.getLanguage()).isEqualTo("python");
        assertThat(py.getCode()).isEqualTo("z + 1");
        assertThat(py.getAssignResultToVar()).isEqualTo("final");
    }

    @Test
    public void parsesMultilineCodeNote() {
        WorkflowData wf = load("wf_multiline_code.puml");
        assertThat(wf.paramNames()).containsExactly("x");
        assertThat(wf.getResultFrom()).isEqualTo("sum");

        CodeData code = (CodeData) wf.getSteps().get(0);
        assertThat(code.getName()).isEqualTo("compute");
        assertThat(code.getLanguage()).isEqualTo("javascript");
        assertThat(code.getAssignResultToVar()).isEqualTo("sum");
        assertThat(code.getCode()).isEqualTo(
                "var doubled = x * 2;\nvar tripled = x * 3;\ndoubled + tripled;");
    }

    @Test
    public void parsesMultilineBodyNote() {
        WorkflowData wf = load("wf_multiline_body.puml");
        assertThat(wf.paramNames()).containsExactly("userId", "amount");
        assertThat(wf.getResultFrom()).isEqualTo("orderId");

        HttpCallData http = (HttpCallData) wf.getSteps().get(0);
        assertThat(http.getName()).isEqualTo("postOrder");
        assertThat(http.getUrl()).isEqualTo("https://example.com/api/orders");
        assertThat(http.getMethod()).isEqualTo("POST");
        assertThat(http.getAssignResultToVar()).isEqualTo("orderId");
        assertThat(http.getBody()).contains("\"userId\": \"${userId}\"");
        assertThat(http.getBody()).contains("\"amount\": \"${amount}\"");
    }

    @Test
    public void parsesHttpAndCodeWorkflow() {
        WorkflowData wf = load("wf_http_and_code.puml");
        assertThat(wf.getName()).isEqualTo("wf_http_and_code");
        assertThat(wf.paramNames()).containsExactly("apiBase");
        assertThat(wf.getResultFrom()).isEqualTo("summary");
        assertThat(wf.getSteps()).hasSize(2);

        HttpCallData http = (HttpCallData) wf.getSteps().get(0);
        assertThat(http.getName()).isEqualTo("fetchGreeting");
        assertThat(http.getUrl()).isEqualTo("${apiBase}/hello");
        assertThat(http.getMethod()).isEqualTo("GET");
        assertThat(http.getAssignResultToVar()).isEqualTo("rawResponse");

        CodeData code = (CodeData) wf.getSteps().get(1);
        assertThat(code.getName()).isEqualTo("buildSummary");
        assertThat(code.getLanguage()).isEqualTo("javascript");
        assertThat(code.getCode()).isEqualTo("\"Fetched: \" + rawResponse");
        assertThat(code.getAssignResultToVar()).isEqualTo("summary");
    }
}
