package ai.mindconnect.workflow.step.form;

import ai.mindconnect.workflow.execution.BaseStepInstance;
import ai.mindconnect.workflow.execution.HaltException;

/**
 * Runs a {@link FormStepData}: it suspends the workflow, and that is all the
 * engine does with it.
 *
 * <p>The form itself is never touched here — it is read from the definition by
 * the admin when it paints the suspended run, not produced at runtime. So this
 * is a plain halt: honour an optional condition, otherwise throw {@link
 * HaltException} to pause. The value the run resumes with comes from the form
 * the user fills, handed back through {@code continueWorkflow}.
 */
public class FormStep extends BaseStepInstance<FormStepData> {

    @Override
    public void execute() throws HaltException {
        FormStepData cfg = getConfig();

        if (cfg.getCondition() != null && !cfg.getCondition().isBlank()) {
            boolean shouldHalt = Boolean.TRUE.equals(resolveExpression(cfg.getCondition()));
            if (!shouldHalt) {
                logDebug("form-step condition false — continuing without showing the form");
                return;
            }
        }

        logDebug("suspending to show form, next=%s", cfg.getNext());
        throw HaltException.builder()
                .stepInstance(this)
                .returnResult(cfg.isReturnResult())
                .next(cfg.getNext())
                .build();
    }
}
