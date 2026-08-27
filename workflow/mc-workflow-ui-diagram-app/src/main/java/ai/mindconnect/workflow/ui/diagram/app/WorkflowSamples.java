package ai.mindconnect.workflow.ui.diagram.app;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Hard-coded demo workflows for the test harness. Each sample exercises a
 * different structural feature so we can eyeball the renderer's output for
 * all the shape categories the builder emits.
 *
 * <p>Samples are returned by a {@link Supplier} rather than a static instance
 * so each request gets a fresh tree — the builder mutates step names in
 * place, and we don't want a /diagram refresh to start failing with
 * "DuplicateStepName" because the previous request already renamed the
 * default-named steps.
 */
public final class WorkflowSamples {

    private WorkflowSamples() {}

    /** Display name → builder. Insertion order is preserved for the UI dropdown. */
    public static final Map<String, Supplier<WorkflowData>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put("linear", WorkflowSamples::linear);
        SAMPLES.put("with-if", WorkflowSamples::withIf);
        SAMPLES.put("parallel-foreach", WorkflowSamples::parallelForeach);
        SAMPLES.put("kitchen-sink", WorkflowSamples::kitchenSink);
    }

    // -----------------------------------------------------------------------
    // Samples
    // -----------------------------------------------------------------------

    private static WorkflowData linear() {
        WorkflowData wf = new WorkflowData();
        wf.setName("greet-user");

        AssignVariablesData assign = new AssignVariablesData();
        assign.setName("init");
        VariableAssignment va = new VariableAssignment();
        va.setVarName("greeting");
        va.setExpressionOrVarName("'hello'");
        assign.getVariableAssignments().add(va);

        HttpCallData fetch = new HttpCallData();
        fetch.setName("fetch-user");
        fetch.setMethod("GET");
        fetch.setUrl("https://api.example.com/users/${userId}");

        CodeData log = new CodeData();
        log.setName("log");
        log.setLanguage("javascript");
        log.setCode("console.log(greeting + ' ' + user.name)");

        wf.addSteps(assign, fetch, log);
        return wf;
    }

    private static WorkflowData withIf() {
        WorkflowData wf = new WorkflowData();
        wf.setName("classify-score");

        // then-branch
        CodeData gold = new CodeData();
        gold.setName("assign-gold");
        gold.setCode("tier = 'gold'");
        BlockData thenBlock = new BlockData();
        thenBlock.addSteps(gold);

        // else-branch
        CodeData silver = new CodeData();
        silver.setName("assign-silver");
        silver.setCode("tier = 'silver'");
        BlockData elseBlock = new BlockData();
        elseBlock.addSteps(silver);

        IfData ifStep = new IfData();
        ifStep.setName("threshold");
        IfData.Condition cond = new IfData.Condition();
        cond.setCondition("spel: score >= 90");
        cond.setThenBlock(thenBlock);
        ifStep.setConditions(cond);
        ifStep.setElseBlock(elseBlock);

        CodeData notify = new CodeData();
        notify.setName("notify");
        notify.setCode("send(tier)");

        wf.addSteps(ifStep, notify);
        return wf;
    }

    private static WorkflowData parallelForeach() {
        WorkflowData wf = new WorkflowData();
        wf.setName("bulk-fetch");

        ForEachData loop = new ForEachData();
        loop.setName("each-user");
        loop.setLoopOver("userIds");
        loop.setRunVar("userId");
        loop.setIndexVar("idx");
        loop.setParallel(true);

        CodeData reduce = new CodeData();
        reduce.setName("reduce");
        reduce.setCode("results.length");

        wf.addSteps(loop, reduce);
        return wf;
    }

    /** Big sample exercising every shape category at once. */
    private static WorkflowData kitchenSink() {
        WorkflowData wf = new WorkflowData();
        wf.setName("kitchen-sink");

        AssignVariablesData init = new AssignVariablesData();
        init.setName("init");

        HttpCallData lookup = new HttpCallData();
        lookup.setName("lookup");
        lookup.setMethod("POST");
        lookup.setUrl("https://api.example.com/lookup");

        // Nested if inside the then-branch
        CodeData inner = new CodeData();
        inner.setName("inner");
        inner.setCode("doInner()");
        BlockData innerBlock = new BlockData();
        innerBlock.addSteps(inner);

        IfData innerIf = new IfData();
        innerIf.setName("inner-check");
        IfData.Condition innerCond = new IfData.Condition();
        innerCond.setCondition("value > 0");
        innerCond.setThenBlock(innerBlock);
        innerIf.setConditions(innerCond);

        BlockData thenBlock = new BlockData();
        thenBlock.addSteps(innerIf);

        // else-branch invokes another workflow
        CallWorkflowData call = new CallWorkflowData();
        call.setName("delegate");
        call.setWorkflow("fallback-handler");
        call.addAssignParam("payload", "lookupResult");
        BlockData elseBlock = new BlockData();
        elseBlock.addSteps(call);

        IfData outerIf = new IfData();
        outerIf.setName("outer-check");
        IfData.Condition outerCond = new IfData.Condition();
        outerCond.setCondition("status == 'ok'");
        outerCond.setThenBlock(thenBlock);
        outerIf.setConditions(outerCond);
        outerIf.setElseBlock(elseBlock);

        // Trailing parallel loop
        ForEachData loop = new ForEachData();
        loop.setName("publish");
        loop.setLoopOver("items");
        loop.setRunVar("item");
        loop.setParallel(true);

        wf.addSteps(init, lookup, outerIf, loop);
        return wf;
    }
}
