package ai.mindconnect.workflow.test;

import ai.mindconnect.workflow.domain.*;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ai.mindconnect.workflow.code.ScriptExpressionResolver}
 * used inline inside step configurations — as conditions in {@code IfData} and as
 * values in {@code AssignVariablesData}.
 *
 * <p>Uses {@link WorkflowTestHelper#programmaticServiceWithResolvers()} so that
 * the {@code javascript:} / {@code beanshell:} prefixes are active.
 */
public class ExpressionResolverIntegrationTest {

    private WorkflowExecutorService svc() {
        return WorkflowTestHelper.programmaticServiceWithResolvers();
    }

    // -----------------------------------------------------------------------
    // Script (JavaScript inline) — assignment values
    // -----------------------------------------------------------------------

    @Test
    public void javaScriptInlineAssignment() {
        WorkflowExecutorService service = svc();

        AssignVariablesData assign = new AssignVariablesData();
        assign.setName("assign");
        assign.getVariableAssignments()
                .add(new VariableAssignment("result", "javascript: base * base"));

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_js_inline");
        wf.addSteps(assign);
        wf.setResultFrom("result");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("base", 7));

        assertThat(result.isSuccess()).isTrue();
        assertThat(((Number) result.getResult()).intValue()).isEqualTo(49);
    }

    @Test
    public void javaScriptInlineConditionInIf() {
        WorkflowExecutorService service = svc();

        AssignVariablesData passAssign = new AssignVariablesData();
        passAssign.setName("pass");
        passAssign.getVariableAssignments().add(new VariableAssignment("verdict", "PASS"));
        BlockData passBlock = new BlockData();
        passBlock.setName("passBlock");
        passBlock.addSteps(passAssign);

        AssignVariablesData failAssign = new AssignVariablesData();
        failAssign.setName("fail");
        failAssign.getVariableAssignments().add(new VariableAssignment("verdict", "FAIL"));
        BlockData failBlock = new BlockData();
        failBlock.setName("failBlock");
        failBlock.addSteps(failAssign);

        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("javascript: score >= 60");
        cond.setThenBlock(passBlock);

        IfData ifData = new IfData();
        ifData.setName("check");
        ifData.setConditions(cond);
        ifData.setElseBlock(failBlock);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_js_if");
        wf.addSteps(ifData);
        wf.setResultFrom("verdict");

        WorkflowResult r1 = service.executeWorkflow(wf, Map.of("score", 75));
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.getResult()).isEqualTo("PASS");

        WorkflowResult r2 = service.executeWorkflow(wf, Map.of("score", 45));
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.getResult()).isEqualTo("FAIL");
    }

    // -----------------------------------------------------------------------
    // BeanShell inline
    // -----------------------------------------------------------------------

    @Test
    public void beanShellInlineAssignment() {
        WorkflowExecutorService service = svc();

        AssignVariablesData assign = new AssignVariablesData();
        assign.setName("assign");
        assign.getVariableAssignments()
                .add(new VariableAssignment("result", "beanshell: x + y"));

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_bsh_inline");
        wf.addSteps(assign);
        wf.setResultFrom("result");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("x", 10, "y", 32));

        assertThat(result.isSuccess()).isTrue();
        assertThat(((Number) result.getResult()).intValue()).isEqualTo(42);
    }
}
