package ai.mindconnect.workflow.edit;

import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepContainerData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Step names, treated as what they actually are: the identity of a step.
 *
 * <p>A workflow already depends on names being unique — {@code JumpToData.jumpTo}
 * and {@code resultFrom} resolve a step by name, and would silently take the
 * first match if two steps shared one. {@link WorkflowMutator} addresses steps
 * the same way. This class is where that assumption is made true instead of
 * merely hoped for: an editor calls {@link #ensureUnique} after loading and
 * {@link #uniqueName} before inserting.
 *
 * <p>The engine also auto-names unnamed steps, but with a UUID — fine for
 * {@code jumpTo}, useless in a UI. The names minted here are readable
 * ({@code code_1}, {@code if_2}) because a user has to see and type them.
 */
public final class StepNames {

    private StepNames() {}

    /** Every step name in the workflow, in tree order. */
    public static Set<String> allNames(WorkflowData wf) {
        Set<String> names = new LinkedHashSet<>();
        collect(wf.getSteps(), names);
        return names;
    }

    /** Whether {@code name} is already used by some step in {@code wf}. */
    public static boolean isTaken(WorkflowData wf, String name) {
        return allNames(wf).contains(name);
    }

    /**
     * A name based on {@code base} that no step in {@code wf} holds yet:
     * {@code base}, else {@code base_2}, {@code base_3}, …
     */
    public static String uniqueName(WorkflowData wf, String base) {
        return uniqueName(allNames(wf), base);
    }

    private static String uniqueName(Set<String> taken, String base) {
        String root = (base == null || base.isBlank()) ? "step" : base.trim();
        if (!taken.contains(root)) return root;
        for (int i = 2; ; i++) {
            String candidate = root + "_" + i;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    /**
     * Gives every unnamed step a readable, unique name, and disambiguates any
     * duplicates a hand-edited file may carry — the second {@code greet} becomes
     * {@code greet_2}. Idempotent: a workflow that is already well-named comes
     * back untouched.
     *
     * @return true if anything was renamed, i.e. the workflow needs saving
     */
    public static boolean ensureUnique(WorkflowData wf) {
        Set<String> taken = new LinkedHashSet<>();
        return ensureUnique(wf.getSteps(), taken);
    }

    private static boolean ensureUnique(List<StepData> steps, Set<String> taken) {
        if (steps == null) return false;
        boolean changed = false;
        for (StepData step : steps) {
            String name = step.getName();
            // Unnamed, or a name someone else already holds — either way it
            // cannot serve as an address, so mint a fresh one.
            if (name == null || name.isBlank() || taken.contains(name)) {
                String base = (name == null || name.isBlank()) ? step.getType() : name;
                step.setName(uniqueName(taken, base));
                changed = true;
            }
            taken.add(step.getName());
            changed |= ensureUnique(childrenOf(step), taken);
        }
        return changed;
    }

    // -----------------------------------------------------------------------

    private static void collect(List<StepData> steps, Set<String> names) {
        if (steps == null) return;
        for (StepData step : steps) {
            if (step.getName() != null && !step.getName().isBlank()) {
                names.add(step.getName());
            }
            collect(childrenOf(step), names);
        }
    }

    /**
     * The steps nested inside {@code step}, flattened across an if's branches.
     * Only used to walk names, so the branch a step sits in does not matter.
     */
    private static List<StepData> childrenOf(StepData step) {
        if (step instanceof IfData ifData) {
            List<StepData> nested = new java.util.ArrayList<>();
            if (ifData.getConditions() != null) {
                for (IfData.Condition condition : ifData.getConditions()) {
                    if (condition.getThenBlock() != null) {
                        nested.addAll(condition.getThenBlock().getSteps());
                    }
                }
            }
            if (ifData.getElseBlock() != null) {
                nested.addAll(ifData.getElseBlock().getSteps());
            }
            return nested;
        }
        if (step instanceof StepContainerData container) {
            return container.getSteps();
        }
        return List.of();
    }
}
