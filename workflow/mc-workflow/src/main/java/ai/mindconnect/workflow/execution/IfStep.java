package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.IfData;

/**
 * Evaluates conditions in order and executes the first matching branch as an
 * inline {@link BlockStep}.  If no condition matches, the {@code elseBlock}
 * is executed (when present).
 *
 * <p>Because each branch is a self-contained block, execution simply
 * continues after the {@code IfStep} — no {@link JumpToStep} plumbing needed.
 */
public class IfStep extends BaseStepInstance<IfData> {

    /**
     * The branch that suspended, if any. An if is a leaf that runs a block, so
     * without this a resume would re-evaluate the condition and run the branch
     * again from its first step — repeating whatever it had already done.
     */
    @lombok.Getter @lombok.Setter
    private BlockStep haltedBranch;

    @Override
    public void execute() throws Exception {
        if (haltedBranch != null) {
            BlockStep branch = haltedBranch;
            haltedBranch = null;
            logDebug("resuming suspended branch");
            runBranch(branch);
            return;
        }

        for (IfData.Condition condition : getConfig().getConditions()) {
            Object resolved = resolveExpression(condition.getCondition());
            if (isTrue(resolved)) {
                logDebug("condition matched: %s", condition.getCondition());
                executeBlock(condition.getThenBlock());
                return;
            }
        }
        // No condition matched — run elseBlock if present
        BlockData elseBlock = getConfig().getElseBlock();
        if (elseBlock != null) {
            logDebug("no condition matched, executing elseBlock");
            executeBlock(elseBlock);
        } else {
            logDebug("no condition matched and no elseBlock — skipping");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void executeBlock(BlockData blockData) throws Exception {
        if (blockData == null) return;
        BlockStep block = new BlockStep();
        block.init(blockData, getContainer(), getWorkflowContext());
        // Record the branch we took. The block borrows the if's own container as
        // its parent scope (that is what lets a branch write to the enclosing
        // scope), so without this it would be a sibling of the if in the
        // execution record, and which branch ran would be unrecoverable.
        getChildInstances().add(block);
        runBranch(block);
    }

    /** Runs (or re-enters) the selected branch and folds its effects into the if. */
    private void runBranch(BlockStep block) throws Exception {
        try {
            block.execute();
        } catch (HaltException halt) {
            haltedBranch = block;
            throw halt;
        }
        // Propagate variables written inside the block up to the parent scope
        block.getVariableScope().getVariablesMap().forEach((name, variable) ->
                getVariableScope().assignValueToParentScope(name, variable.getValue(), null));
        // Propagate the block's result as the IfStep's result
        setResult(block.getResult());
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return false;
    }
}
