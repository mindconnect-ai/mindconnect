package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.ForEachData;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Iterates over a list variable and executes the child steps for each element.
 * Supports sequential and parallel execution modes.
 */
public class ForEachStep extends BaseStepContainerInstance<ForEachData> {

    /** Iterations already finished — equally, the index of the one in flight. */
    @lombok.Getter @lombok.Setter
    private int currentRunIndex = 0;

    private Object currentRunVarValue;

    /** Results of the finished iterations. Part of the resume state. */
    @lombok.Getter @lombok.Setter
    private List<Object> resultList = new ArrayList<>();

    /** The iteration that suspended, if any. Re-entered rather than re-run. */
    @lombok.Getter @lombok.Setter
    private BlockStep haltedBlock;

    /**
     * Rebuilds the block for one iteration exactly as the loop itself would —
     * used when restoring a suspended loop from a snapshot, where the iteration
     * that was interrupted has to come back as the same shape it had.
     */
    public BlockStep rebuildIterationBlock(int index, Object item) {
        int saved = currentRunIndex;
        currentRunIndex = index;
        try {
            return createBlock(item);
        } finally {
            currentRunIndex = saved;
        }
    }

    @Override
    public void execute() throws Exception {
        Iterable<?> iterable = resolveIterable();

        if (getConfig().isParallel()) {
            executeParallel(iterable);
        } else {
            executeSequential(iterable);
        }

        applyJoinStrategy();
    }

    // -----------------------------------------------------------------------
    // Iterable resolution
    // -----------------------------------------------------------------------

    private Iterable<?> resolveIterable() {
        String loopOver = getConfig().getLoopOver();
        Object value;

        ExpressionResolver resolver = getExpressionResolver();
        if (resolver != null && resolver.containsExpression(loopOver)) {
            value = evalExpression(loopOver);
        } else {
            VariableScope.Variable v = getVariableScope().getVariable(loopOver);
            value = v != null ? v.getValue() : null;
            // If stored as JSON string, parse to list via JsonMapper
            if (value instanceof String text && getWorkflowContext().getJsonMapper() != null) {
                value = getWorkflowContext().getJsonMapper().parseList(text, Map.class);
            }
        }

        if (value instanceof Iterable<?> it) return it;
        throw new IllegalStateException(
                "for_each '" + getConfig().getName() + "': loop_over='" + loopOver
                + "' is not iterable — actual type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    // -----------------------------------------------------------------------
    // Sequential mode
    // -----------------------------------------------------------------------

    /**
     * Runs the loop, and on a resume picks up exactly where it stopped: the
     * iterations already counted in {@code currentRunIndex} are skipped, the one
     * that suspended is re-entered rather than restarted, and the rest follow as
     * usual. Without that, a resume would re-run the whole loop — repeating every
     * side effect the finished iterations had already had.
     */
    private void executeSequential(Iterable<?> iterable) throws Exception {
        int index = 0;
        for (Object item : iterable) {
            if (index++ < currentRunIndex) {
                continue; // done in an earlier pass
            }
            BlockStep block;
            if (haltedBlock != null) {
                block = haltedBlock;
                haltedBlock = null;
                logDebug("resuming suspended iteration %d", currentRunIndex);
            } else {
                block = createBlock(item);
            }
            try {
                block.execute();
            } catch (HaltException halt) {
                haltedBlock = block;
                throw halt;
            }
            resultList.add(block.getResult());
            currentRunIndex++;
        }
    }

    // -----------------------------------------------------------------------
    // Parallel mode
    // -----------------------------------------------------------------------

    private void executeParallel(Iterable<?> iterable) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        List<Future<BlockStep>> futures = new ArrayList<>();

        try {
            for (Object item : iterable) {
                BlockStep block = createBlock(item);
                futures.add(executor.submit(() -> {
                    block.execute();
                    return block;
                }));
                currentRunIndex++;
            }
        } finally {
            executor.shutdown();
        }

        List<Throwable> errors = new ArrayList<>();
        for (Future<BlockStep> future : futures) {
            try {
                resultList.add(future.get().getResult());
            } catch (ExecutionException ex) {
                errors.add(ex.getCause());
            }
        }
        // A halt suspends *one* line of execution, and a resume picks up from
        // one pointer. Several branches running at once can each halt, and there
        // is no single place to come back to — so say so, instead of silently
        // turning the suspension into an error like it used to.
        for (Throwable error : errors) {
            if (error instanceof HaltException) {
                throw new IllegalStateException(
                        "for_each '" + getConfig().getName() + "': a halt inside a parallel "
                        + "for-each cannot be resumed. Set parallel=false to suspend in a loop.");
            }
        }
        if (!errors.isEmpty()) {
            throw new MultipleExceptionsException(errors);
        }
    }

    // -----------------------------------------------------------------------
    // Block factory
    // -----------------------------------------------------------------------

    private BlockStep createBlock(Object item) {
        currentRunVarValue = item;
        BlockData blockData = new BlockData();
        blockData.setName(getConfig().getName() + "_" + currentRunIndex);
        blockData.setResultFrom(getConfig().getResultFrom());
        blockData.setSteps(new ArrayList<>(getConfig().getSteps()));

        BlockStep block = new BlockStep();
        block.init(blockData, this, getWorkflowContext());
        block.getVariableScope().assignValue(getConfig().getRunVar(), item, null);
        block.getVariableScope().assignValue(getConfig().getIndexVar(), currentRunIndex, null);
        getStepInstances().add(block);
        return block;
    }

    // -----------------------------------------------------------------------
    // Result assembly
    // -----------------------------------------------------------------------

    private void applyJoinStrategy() {
        if (getConfig().isJoinResults()) {
            String delimiter = Objects.toString(getConfig().getJoinDelimiter(), "");
            setResult(resultList.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(delimiter)));
        } else {
            setResult(resultList);
        }
    }
}
