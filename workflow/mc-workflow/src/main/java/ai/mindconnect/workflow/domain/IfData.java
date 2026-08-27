package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Conditional branching step.
 *
 * <p>Evaluates each {@link Condition} in order; the first whose
 * {@code condition} expression is {@code true} executes its inline
 * {@code thenBlock}.  If no condition matches, the optional {@code elseBlock}
 * is executed.
 *
 * <p>Because each branch is a self-contained {@link BlockData}, no
 * {@link JumpToData} steps are needed — execution continues after the
 * {@code IfData} step once the chosen block finishes.
 *
 * <p>Example (programmatic):
 * <pre>{@code
 * BlockData highBlock = new BlockData();
 * highBlock.addSteps(assignTier("gold"));
 *
 * BlockData lowBlock = new BlockData();
 * lowBlock.addSteps(assignTier("silver"));
 *
 * IfData.Condition cond = new IfData.Condition();
 * cond.setCondition("spel: score >= 90");
 * cond.setThenBlock(highBlock);
 *
 * IfData ifData = new IfData();
 * ifData.setConditions(cond);
 * ifData.setElseBlock(lowBlock);
 * }</pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IfData extends BaseStepData {

    @Data
    public static class Condition {
        /** Boolean expression evaluated against the current variable scope. */
        private String condition;
        /** Steps to execute when this condition is {@code true}. */
        private BlockData thenBlock;
    }

    /** Conditions evaluated in order; the first matching one wins. */
    private Condition[] conditions = new Condition[0];

    /** Executed when no condition matches. May be {@code null}. */
    private BlockData elseBlock;

    public void setConditions(Condition... conditions) {
        this.conditions = conditions;
    }
}
