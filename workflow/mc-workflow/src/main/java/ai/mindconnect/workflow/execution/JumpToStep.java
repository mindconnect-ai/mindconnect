package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.JumpToData;

/** Unconditionally redirects execution to a named step in the parent container. */
public class JumpToStep extends BaseStepInstance<JumpToData> {

    @Override
    public void execute() {
        String target = getConfig().getJumpTo();
        ExpressionResolver resolver = getExpressionResolver();
        if (resolver != null && resolver.containsExpression(target)) {
            target = (String) resolveExpression(target);
        }
        logDebug("jumpTo -> %s", target);
        getContainer().jumpTo(target);
    }
}
