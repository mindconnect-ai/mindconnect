package ai.mindconnect.workflow.persist;

import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepContainerData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A fingerprint of a workflow's <em>shape</em>: the order, nesting, names and
 * types of its steps.
 *
 * <p>Deliberately not a hash of the whole definition. A suspended instance
 * resumes by index — "carry on at step 3 of this block" — so what must not
 * change is the arrangement of the steps. Editing the body of a code step, an
 * HTTP URL or a condition leaves every pointer valid and should not invalidate a
 * sleeping instance; inserting, deleting, reordering or renaming a step moves
 * the ground under it and must.
 */
public final class DefinitionFingerprint {

    private DefinitionFingerprint() {}

    public static String of(WorkflowData workflow) {
        StringBuilder shape = new StringBuilder();
        appendSteps(shape, workflow.getSteps());
        return sha256(shape.toString());
    }

    private static void appendSteps(StringBuilder out, List<StepData> steps) {
        out.append('[');
        if (steps != null) {
            for (StepData step : steps) {
                out.append(step.getType()).append(':').append(step.getName());
                appendChildren(out, step);
                out.append(',');
            }
        }
        out.append(']');
    }

    private static void appendChildren(StringBuilder out, StepData step) {
        if (step instanceof IfData ifData) {
            if (ifData.getConditions() != null) {
                for (IfData.Condition condition : ifData.getConditions()) {
                    out.append("then");
                    appendSteps(out, condition.getThenBlock() == null
                            ? null : condition.getThenBlock().getSteps());
                }
            }
            out.append("else");
            appendSteps(out, ifData.getElseBlock() == null
                    ? null : ifData.getElseBlock().getSteps());
        } else if (step instanceof StepContainerData container) {
            appendSteps(out, container.getSteps());
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
