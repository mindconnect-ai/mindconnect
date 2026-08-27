package ai.mindconnect.workflow.persist;

import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepContainerData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.BaseStepContainerInstance;
import ai.mindconnect.workflow.execution.BlockStep;
import ai.mindconnect.workflow.execution.ForEachStep;
import ai.mindconnect.workflow.execution.IfStep;
import ai.mindconnect.workflow.execution.StepContainerInstance;
import ai.mindconnect.workflow.execution.StepInstance;
import ai.mindconnect.workflow.execution.VariableScope;
import ai.mindconnect.workflow.execution.WorkflowContext;
import ai.mindconnect.workflow.execution.WorkflowInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a halted {@link WorkflowInstance} into a {@link WorkflowInstanceSnapshot}
 * and back.
 *
 * <p><b>Snapshot, not serialisation.</b> A live instance is three things tangled
 * together: the definition it is running, the runtime collaborators it borrowed
 * (script engines, expression resolvers, event listeners, an output writer), and
 * the progress it actually made. Only the last of those is worth keeping, and the
 * middle one cannot go on disk at all. So this captures the progress explicitly
 * and rebuilds the rest — {@link #restore} takes a fresh {@link WorkflowContext}
 * and hands back an instance the executor can simply continue.
 */
public final class WorkflowInstanceSnapshots {

    /**
     * Built into every workflow's root scope by the executor: the process
     * environment and every system property. Never persisted — it would write
     * the machine's secrets to disk, and it is rebuilt on the next run anyway.
     */
    private static final String BUILTIN_ENV = "env";

    private WorkflowInstanceSnapshots() {}

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
     * Captures the resume point of a halted instance.
     *
     * @param suspendedAt epoch millis, supplied by the caller
     */
    public static WorkflowInstanceSnapshot capture(WorkflowInstance instance, long suspendedAt) {
        WorkflowData definition = instance.getConfig();

        WorkflowInstanceSnapshot snapshot = new WorkflowInstanceSnapshot();
        snapshot.setWorkflowName(definition.getName());
        snapshot.setDefinitionFingerprint(DefinitionFingerprint.of(definition));
        snapshot.setSuspendedAt(suspendedAt);
        snapshot.setRoot(captureFrame(instance, FrameSnapshot.Kind.ROOT, -1));
        return snapshot;
    }

    private static FrameSnapshot captureFrame(StepInstance<?> instance,
                                              FrameSnapshot.Kind kind, int index) {
        FrameSnapshot frame = new FrameSnapshot();
        frame.setKind(kind);
        frame.setIndex(index);
        frame.setStepName(instance.getConfig().getName());
        frame.setVariables(captureVariables(instance.getVariableScope(),
                kind == FrameSnapshot.Kind.ROOT));
        frame.setResult(instance.getResult());

        if (instance instanceof BaseStepContainerInstance<?> container) {
            frame.setNextStepIndex(container.getNextStepIndex());
            frame.setCurrentStepIndex(container.getCurrentStepIndex());
        }

        if (instance instanceof ForEachStep loop) {
            frame.setCurrentRunIndex(loop.getCurrentRunIndex());
            frame.setResultList(new ArrayList<>(loop.getResultList()));
            if (loop.getHaltedBlock() != null) {
                frame.setHaltedChild(captureFrame(loop.getHaltedBlock(),
                        FrameSnapshot.Kind.FOREACH_ITERATION, loop.getCurrentRunIndex()));
            }
            return frame;
        }

        if (instance instanceof IfStep ifStep) {
            BlockStep branch = ifStep.getHaltedBranch();
            if (branch != null) {
                frame.setHaltedChild(captureFrame(branch, branchKind(ifStep, branch),
                        branchIndex(ifStep, branch)));
            }
            return frame;
        }

        if (instance instanceof BaseStepContainerInstance<?> container
                && container.getHaltedChild() != null) {
            StepInstance<?> child = container.getHaltedChild();
            frame.setHaltedChild(captureFrame(child, FrameSnapshot.Kind.STEP,
                    indexInContainer(container, child)));
        }
        return frame;
    }

    /** The variables declared directly in this scope — {@code env} excluded. */
    private static Map<String, Object> captureVariables(VariableScope scope, boolean isRoot) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (scope == null || scope.getVariablesMap() == null) return out;
        scope.getVariablesMap().forEach((name, variable) -> {
            if (isRoot && BUILTIN_ENV.equals(name)) return;
            out.put(name, variable.getValue());
        });
        return out;
    }

    private static int indexInContainer(BaseStepContainerInstance<?> container, StepInstance<?> child) {
        List<StepData> steps = container.getConfig().getSteps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) == child.getConfig()) return i;
        }
        throw new IllegalStateException("Suspended step '" + child.getConfig().getName()
                + "' is not a child of '" + container.getConfig().getName() + "'");
    }

    private static FrameSnapshot.Kind branchKind(IfStep ifStep, BlockStep branch) {
        return branchIndex(ifStep, branch) < 0
                ? FrameSnapshot.Kind.IF_ELSE : FrameSnapshot.Kind.IF_THEN;
    }

    /** The condition whose then-block this is, or -1 for the else-block. */
    private static int branchIndex(IfStep ifStep, BlockStep branch) {
        IfData.Condition[] conditions = ifStep.getConfig().getConditions();
        if (conditions != null) {
            for (int i = 0; i < conditions.length; i++) {
                if (conditions[i].getThenBlock() == branch.getConfig()) return i;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // Reading a snapshot without restoring it
    // -----------------------------------------------------------------------

    /**
     * The halt step this instance is waiting at.
     *
     * <p>Lets a caller ask a suspended instance what it is waiting <em>for</em>
     * — {@code HaltData#getResumeParams()} — and
     * collect it, without having to rebuild the instance first. That is the whole
     * difference between "this run is paused" and "this run is paused, waiting
     * for a user message".
     *
     * <p>Empty if the snapshot's deepest frame was not sitting on a halt, which
     * would mean the definition no longer matches it.
     */
    public static Optional<HaltData> pendingHalt(WorkflowData definition,
                                                 WorkflowInstanceSnapshot snapshot) {
        StepData container = definition;
        FrameSnapshot frame = snapshot.getRoot();
        while (frame.getHaltedChild() != null) {
            container = descend(container, frame.getHaltedChild());
            frame = frame.getHaltedChild();
        }
        List<StepData> steps = stepsOf(container);
        int index = frame.getCurrentStepIndex();
        if (steps == null || index < 0 || index >= steps.size()) {
            return Optional.empty();
        }
        return steps.get(index) instanceof HaltData halt ? Optional.of(halt) : Optional.empty();
    }

    /** The definition the child frame was executing, given its parent's. */
    private static StepData descend(StepData container, FrameSnapshot child) {
        return switch (child.getKind()) {
            case STEP -> stepsOf(container).get(child.getIndex());
            // An iteration runs a copy of the loop's own body, so the step list
            // does not move — the loop itself stays the container.
            case FOREACH_ITERATION -> container;
            case IF_THEN -> ((IfData) container).getConditions()[child.getIndex()].getThenBlock();
            case IF_ELSE -> ((IfData) container).getElseBlock();
            case ROOT -> throw new IllegalStateException("ROOT cannot be a child frame");
        };
    }

    /** The steps a container holds, or null when it holds none (an if). */
    private static List<StepData> stepsOf(StepData container) {
        return container instanceof StepContainerData c ? c.getSteps() : null;
    }

    // -----------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------

    /**
     * Rebuilds a suspended instance so the executor can continue it.
     *
     * @param definition the workflow as it stands <em>now</em> — checked against
     *                   the snapshot's fingerprint before anything is rebuilt
     * @param context    a fresh context; the old one's script engines and
     *                   listeners were never persisted and are not wanted
     * @throws DefinitionChangedException if the workflow's shape has changed, in
     *                                    which case every index in the snapshot
     *                                    may point at a different step
     */
    public static WorkflowInstance restore(WorkflowData definition,
                                           WorkflowInstanceSnapshot snapshot,
                                           WorkflowContext context) {
        String current = DefinitionFingerprint.of(definition);
        if (!current.equals(snapshot.getDefinitionFingerprint())) {
            throw new DefinitionChangedException(snapshot.getWorkflowName());
        }

        WorkflowInstance instance = new WorkflowInstance();
        instance.init(definition, null, context);

        FrameSnapshot root = snapshot.getRoot();
        applyFrame(instance, root);
        restoreHaltedChild(instance, root, context);
        return instance;
    }

    /** Puts one frame's progress back onto a freshly built instance. */
    private static void applyFrame(StepInstance<?> instance, FrameSnapshot frame) {
        VariableScope scope = instance.getVariableScope();
        frame.getVariables().forEach((name, value) -> scope.assignValue(name, value, null));

        if (instance instanceof BaseStepContainerInstance<?> container) {
            container.setNextStepIndex(frame.getNextStepIndex());
            container.setCurrentStepIndex(frame.getCurrentStepIndex());
            container.setResult(frame.getResult());
        }
        if (instance instanceof ForEachStep loop) {
            loop.setCurrentRunIndex(frame.getCurrentRunIndex());
            loop.setResultList(new ArrayList<>(frame.getResultList()));
        }
    }

    private static void restoreHaltedChild(StepInstance<?> parent, FrameSnapshot parentFrame,
                                           WorkflowContext context) {
        FrameSnapshot childFrame = parentFrame.getHaltedChild();
        if (childFrame == null) return;

        StepInstance<?> child = rebuildChild(parent, childFrame, context);
        applyFrame(child, childFrame);
        restoreHaltedChild(child, childFrame, context);
        attachHaltedChild(parent, child);
    }

    /** Rebuilds the suspended child from the definition, per how it was created. */
    private static StepInstance<?> rebuildChild(StepInstance<?> parent, FrameSnapshot frame,
                                                WorkflowContext context) {
        switch (frame.getKind()) {
            case STEP -> {
                BaseStepContainerInstance<?> container = (BaseStepContainerInstance<?>) parent;
                StepData stepData = container.getConfig().getSteps().get(frame.getIndex());
                expectName(stepData, frame);
                @SuppressWarnings("unchecked")
                StepInstance<StepData> child = (StepInstance<StepData>)
                        context.getStepInstanceFactory().instantiate(stepData);
                child.init(stepData, (StepContainerInstance<?>) container, context);
                return child;
            }
            case FOREACH_ITERATION -> {
                // The loop mints a fresh block per iteration, so the block that
                // was interrupted does not exist in the definition — the loop
                // rebuilds it exactly as it built the first time round. The item
                // is not needed: the iteration's own variables (item, index) are
                // in the frame and go back in with applyFrame.
                ForEachStep loop = (ForEachStep) parent;
                return loop.rebuildIterationBlock(frame.getIndex(), null);
            }
            case IF_THEN, IF_ELSE -> {
                IfStep ifStep = (IfStep) parent;
                BlockData blockData = frame.getKind() == FrameSnapshot.Kind.IF_ELSE
                        ? ifStep.getConfig().getElseBlock()
                        : ifStep.getConfig().getConditions()[frame.getIndex()].getThenBlock();
                BlockStep block = new BlockStep();
                // Same parent as when it ran: the if's own container, which is
                // what lets a branch write into the enclosing scope.
                block.init(blockData, ifStep.getContainer(), context);
                ifStep.getChildInstances().add(block);
                return block;
            }
            default -> throw new IllegalStateException("Cannot rebuild a " + frame.getKind() + " frame");
        }
    }

    private static void attachHaltedChild(StepInstance<?> parent, StepInstance<?> child) {
        if (parent instanceof ForEachStep loop) {
            loop.setHaltedBlock((BlockStep) child);
        } else if (parent instanceof IfStep ifStep) {
            ifStep.setHaltedBranch((BlockStep) child);
        } else if (parent instanceof BaseStepContainerInstance<?> container) {
            container.setHaltedChild(child);
        } else {
            throw new IllegalStateException(
                    "A " + parent.getClass().getSimpleName() + " cannot hold a suspended child");
        }
    }

    private static void expectName(StepData stepData, FrameSnapshot frame) {
        if (frame.getStepName() != null && !frame.getStepName().equals(stepData.getName())) {
            throw new DefinitionChangedException(
                    "Expected step '" + frame.getStepName() + "' at index " + frame.getIndex()
                    + ", found '" + stepData.getName() + "'");
        }
    }

    /** The workflow changed shape while an instance slept; resuming would be a guess. */
    public static class DefinitionChangedException extends RuntimeException {
        public DefinitionChangedException(String message) {
            super("Cannot resume: the workflow has been edited since it was suspended. " + message);
        }
    }
}
