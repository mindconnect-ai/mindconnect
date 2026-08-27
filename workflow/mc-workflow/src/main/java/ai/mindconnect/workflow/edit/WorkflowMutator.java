package ai.mindconnect.workflow.edit;

import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.List;
import java.util.Objects;

/**
 * Server-side tree mutations on a {@link WorkflowData}, addressed by
 * {@code stepRef} — the step's name, which is unique within a workflow
 * (see {@link StepNames}). Every edit a UI offers becomes one call here, so
 * the diagram editor and the workflow admin share one mutation layer instead
 * of each inventing its own.
 *
 * <h2>Why names, not positions</h2>
 * A positional address ({@code "0.then0.1"}) is only valid for the snapshot it
 * was rendered from: delete one step and every address below it silently
 * shifts onto a different step. A name survives insertion, deletion and
 * reordering — and when it no longer resolves, {@link #find} returns
 * {@code null} so the caller can answer "gone" instead of editing the wrong
 * step. It is also the address the engine itself uses: {@code JumpToData.jumpTo}
 * and {@code resultFrom} both reference steps by name.
 *
 * <h2>Tree model</h2>
 * {@link WorkflowData} is a tree of step lists:
 * <ul>
 *   <li>{@code WorkflowData.steps} — the top-level sequence</li>
 *   <li>{@code BlockData.steps}, {@code ForEachData.steps} — nested sequences</li>
 *   <li>{@code IfData.conditions[].thenBlock.steps}, {@code IfData.elseBlock.steps}
 *       — decision branches</li>
 * </ul>
 * All mutation methods traverse this tree recursively, so a {@code stepRef}
 * in any nesting depth resolves correctly.
 *
 * <h2>Locator semantics</h2>
 * {@link StepLocation} is the result of finding a step: the containing list
 * and the index within that list. Returning the location (instead of just
 * the step) lets callers do positional operations like "insert after" or
 * "delete" without re-walking the tree.
 */
public class WorkflowMutator {

    /** Where a step lives in the tree: which list, at which index. */
    public record StepLocation(List<StepData> container, int index, StepData step) {}

    /**
     * Locates a step by its unique name. Returns {@code null} if no step
     * matches — callers should treat that as a stale UI event and return
     * a 404 rather than crashing.
     */
    public StepLocation find(WorkflowData wf, String stepRef) {
        Objects.requireNonNull(stepRef);
        return findInList(wf.getSteps(), stepRef);
    }

    /**
     * Inserts {@code newStep} immediately after the step identified by
     * {@code afterStepRef}, in the same list. The new step's name should be
     * unique within the workflow — callers typically set a placeholder
     * (e.g. {@code "step_new"}) and let the next builder run auto-generate
     * a safe name.
     *
     * @throws IllegalArgumentException if {@code afterStepRef} is not found
     */
    public void insertAfter(WorkflowData wf, String afterStepRef, StepData newStep) {
        StepLocation loc = find(wf, afterStepRef);
        if (loc == null) {
            throw new IllegalArgumentException("No step named '" + afterStepRef + "'");
        }
        loc.container.add(loc.index + 1, newStep);
    }

    /**
     * Inserts {@code newStep} at the very beginning of the workflow's
     * top-level steps. Used by the editor when the user clicks the plus
     * button on the edge that connects the start marker to the first step
     * (no "after" reference exists yet).
     */
    public void insertAtStart(WorkflowData wf, StepData newStep) {
        wf.getSteps().add(0, newStep);
    }

    /**
     * Insertion position within a container's children. {@code FIRST} prepends,
     * {@code LAST} appends.
     */
    public enum Position { FIRST, LAST }

    /**
     * Inserts {@code newStep} into the children list of the container
     * identified by {@code containerPath} — a tiny DSL naming a container and,
     * for an if, which branch of it:
     *
     * <ul>
     *   <li>{@code block:<stepName>} — into the steps of a {@link BlockData}</li>
     *   <li>{@code foreach:<stepName>} — into the body of a {@link ForEachData}</li>
     *   <li>{@code if:<stepName>:then:<idx>} — into the then-block of the
     *       {@code idx}-th condition of an {@link IfData}</li>
     *   <li>{@code if:<stepName>:else} — into the else-block of an If</li>
     * </ul>
     *
     * <p>Throws {@link IllegalArgumentException} if the path doesn't resolve.
     */
    public void insertIntoContainer(WorkflowData wf, String containerPath,
                                    Position pos, StepData newStep) {
        List<StepData> target = resolveContainerList(wf, containerPath);
        if (pos == Position.FIRST) {
            target.add(0, newStep);
        } else {
            target.add(newStep);
        }
    }

    private List<StepData> resolveContainerList(WorkflowData wf, String containerPath) {
        if (containerPath == null) {
            throw new IllegalArgumentException("containerPath must not be null");
        }
        String[] parts = containerPath.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed containerPath: " + containerPath);
        }
        String kind = parts[0];
        String stepName = parts[1];

        StepLocation loc = find(wf, stepName);
        if (loc == null) {
            throw new IllegalArgumentException("No step named '" + stepName + "' for container path " + containerPath);
        }
        return switch (kind) {
            case "block" -> {
                if (!(loc.step instanceof BlockData b)) {
                    throw new IllegalArgumentException(stepName + " is not a block");
                }
                yield b.getSteps();
            }
            case "foreach" -> {
                if (!(loc.step instanceof ForEachData fe)) {
                    throw new IllegalArgumentException(stepName + " is not a foreach");
                }
                yield fe.getSteps();
            }
            case "if" -> {
                if (!(loc.step instanceof IfData ifData)) {
                    throw new IllegalArgumentException(stepName + " is not an if");
                }
                if (parts.length < 3) {
                    throw new IllegalArgumentException("If path needs then:<idx> or else");
                }
                if ("else".equals(parts[2])) {
                    if (ifData.getElseBlock() == null) ifData.setElseBlock(new BlockData());
                    yield ifData.getElseBlock().getSteps();
                }
                if ("then".equals(parts[2])) {
                    if (parts.length < 4) {
                        throw new IllegalArgumentException("If then path needs branch index");
                    }
                    int idx = Integer.parseInt(parts[3]);
                    var conditions = ifData.getConditions();
                    if (conditions == null || idx >= conditions.length) {
                        throw new IllegalArgumentException("If branch index out of range: " + idx);
                    }
                    var cond = conditions[idx];
                    if (cond.getThenBlock() == null) cond.setThenBlock(new BlockData());
                    yield cond.getThenBlock().getSteps();
                }
                throw new IllegalArgumentException("Unknown if branch kind: " + parts[2]);
            }
            default -> throw new IllegalArgumentException("Unknown container kind: " + kind);
        };
    }

    /**
     * Removes the step identified by {@code stepRef} from its containing
     * list. Returns {@code true} if a step was removed, {@code false} if
     * no such step exists (lets the controller distinguish "stale UI"
     * from "successful delete").
     */
    public boolean delete(WorkflowData wf, String stepRef) {
        StepLocation loc = find(wf, stepRef);
        if (loc == null) return false;
        loc.container.remove(loc.index);
        return true;
    }

    /**
     * Renames the step identified by {@code stepRef}. The caller is responsible
     * for ensuring {@code newName} doesn't collide — {@link StepNames#isTaken}
     * answers that, and a duplicate name would make both this mutator and the
     * engine's {@code jumpTo} resolve to whichever step comes first.
     *
     * @return true if a step was renamed; false if {@code stepRef} not found
     */
    public boolean rename(WorkflowData wf, String stepRef, String newName) {
        StepLocation loc = find(wf, stepRef);
        if (loc == null) return false;
        loc.step.setName(newName);
        return true;
    }


    // -----------------------------------------------------------------------
    // Recursive search
    // -----------------------------------------------------------------------

    private StepLocation findInList(List<StepData> steps, String ref) {
        if (steps == null) return null;
        for (int i = 0; i < steps.size(); i++) {
            StepData step = steps.get(i);
            if (ref.equals(step.getName())) {
                return new StepLocation(steps, i, step);
            }
            // Recurse into container types. Order matters only for performance;
            // step names are globally unique within a workflow so the first
            // hit is the only hit.
            StepLocation nested = findInside(step, ref);
            if (nested != null) return nested;
        }
        return null;
    }

    private StepLocation findInside(StepData step, String ref) {
        if (step instanceof IfData ifData) {
            if (ifData.getConditions() != null) {
                for (IfData.Condition c : ifData.getConditions()) {
                    if (c.getThenBlock() != null) {
                        StepLocation hit = findInList(c.getThenBlock().getSteps(), ref);
                        if (hit != null) return hit;
                    }
                }
            }
            if (ifData.getElseBlock() != null) {
                return findInList(ifData.getElseBlock().getSteps(), ref);
            }
            return null;
        }
        if (step instanceof ForEachData fe) {
            return findInList(fe.getSteps(), ref);
        }
        if (step instanceof BlockData block) {
            return findInList(block.getSteps(), ref);
        }
        return null;
    }
}
