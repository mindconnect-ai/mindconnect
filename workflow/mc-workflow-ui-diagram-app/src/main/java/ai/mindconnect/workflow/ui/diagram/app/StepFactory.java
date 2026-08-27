package ai.mindconnect.workflow.ui.diagram.app;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.JumpToData;
import ai.mindconnect.workflow.domain.StepData;

import java.util.List;

/**
 * Whitelist of step types the editor can insert, plus the factory method
 * that materialises each one with sensible defaults. Centralised here so
 * the controller, the front-end picker, and any future tests agree on a
 * single source of truth.
 *
 * <p>The string keys match the {@code stepType} the builder emits in
 * {@code UiDiagramNode.data["stepType"]}, so a node clicked in the diagram
 * round-trips to the same identifier we use when inserting a new one.
 */
public final class StepFactory {

    private StepFactory() {}

    /** Display labels paired with the {@code stepType} key. Insertion order = palette order. */
    public static final List<TypeOption> AVAILABLE = List.of(
        new TypeOption("code",            "Code"),
        new TypeOption("httpcall",        "HTTP Call"),
        new TypeOption("assignvariables", "Assign Variables"),
        new TypeOption("if",              "If / Decision"),
        new TypeOption("foreach",         "ForEach"),
        new TypeOption("callworkflow",    "Call Workflow"),
        new TypeOption("jumpto",          "Jump To"),
        new TypeOption("block",           "Block")
    );

    public record TypeOption(String stepType, String label) {}

    /**
     * Creates a fresh {@link StepData} of the requested type with a placeholder
     * name. The placeholder will be overwritten by
     * {@link ai.mindconnect.workflow.ui.diagram.WorkflowDiagramBuilder}'s
     * auto-naming on the next render, so the name doesn't have to be unique
     * here — it just needs to be present (the builder treats blank names as
     * auto-generation triggers).
     *
     * @throws IllegalArgumentException for unknown step types
     */
    public static StepData create(String stepType) {
        StepData step = switch (stepType.toLowerCase()) {
            case "code" -> {
                var c = new CodeData();
                c.setLanguage("javascript");
                yield c;
            }
            case "httpcall" -> {
                var h = new HttpCallData();
                h.setMethod("GET");
                yield h;
            }
            case "assignvariables" -> new AssignVariablesData();
            case "if"              -> {
                // Seed with one empty condition + empty then-block so the
                // freshly-inserted If immediately renders with a visible
                // then-branch container the user can drop steps into. The
                // condition string stays blank — the user fills it via the
                // side panel.
                var ifData = new IfData();
                var cond = new IfData.Condition();
                cond.setCondition("");
                cond.setThenBlock(new BlockData());
                ifData.setConditions(cond);
                yield ifData;
            }
            case "foreach"         -> new ForEachData();
            case "callworkflow"    -> new CallWorkflowData();
            case "jumpto"          -> new JumpToData();
            case "block"           -> new BlockData();
            default -> throw new IllegalArgumentException("Unknown step type: " + stepType);
        };
        // Blank name → the builder's auto-naming kicks in on next render.
        step.setName("");
        return step;
    }
}
