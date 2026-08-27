package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.HaltData;

/** Suspends workflow execution, optionally resolving a return value. */
public class HaltStep extends BaseStepInstance<HaltData> {

    @Override
    public void execute() throws HaltException {
        HaltData cfg = getConfig();

        // If a condition is specified, only halt when it evaluates to true
        if (cfg.getCondition() != null && !cfg.getCondition().isBlank()) {
            boolean shouldHalt = Boolean.TRUE.equals(resolveExpression(cfg.getCondition()));
            if (!shouldHalt) {
                logDebug("halt condition false — continuing");
                return;
            }
        }

        Object result = resolveReturnResult();
        setResult(result);
        logDebug("halting, result=%s, next=%s", result, cfg.getNext());
        throw HaltException.builder()
                .stepInstance(this)
                .returnResult(cfg.isReturnResult())
                .next(cfg.getNext())
                .build();
    }

    private Object resolveReturnResult() {
        String expr = getConfig().getReturnResultExpression();
        if (expr == null || expr.isBlank()) return null;
        ExpressionResolver resolver = getExpressionResolver();
        if (resolver != null && resolver.containsExpression(expr)) {
            return resolveExpression(expr);
        }
        VariableScope.Variable v = getVariableScope().getParentScope() != null
                ? getVariableScope().getParentScope().getVariable(expr) : null;
        return v != null ? v.getValue() : expr;
    }
}
