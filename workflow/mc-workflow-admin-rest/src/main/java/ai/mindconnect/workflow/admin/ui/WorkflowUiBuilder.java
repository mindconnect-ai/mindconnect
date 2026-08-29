package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiMenuButton;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.StepContainerData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders a {@link WorkflowData} tree into nested semantic-ui stacks.
 *
 * <p>The editor reads as one list: each step is a row — {@code [type]
 * [name] … [⋯]} — joined to its neighbours without gaps, with the row's
 * actions (details, edit, delete) behind a single overflow menu on the
 * right. Container steps (block, for-each, if-branches) recurse, their
 * children indented underneath rather than boxed.
 *
 * <p><b>Steps are addressed by name.</b> Every action a card emits carries the
 * step's name, not its position in the tree — so a button still sitting in an
 * open dialog or a second tab keeps pointing at the step the user meant, even
 * after something above it was deleted or moved. Names are unique per workflow
 * ({@link ai.mindconnect.workflow.edit.StepNames}) and are the same address the
 * engine itself uses for {@code jumpTo} / {@code resultFrom}.
 *
 * <p>The builder is presentation-only; it never mutates the workflow.
 */
public class WorkflowUiBuilder {

    /** The pseudo-container standing for the workflow's own top-level list. */
    public static final String ROOT = "root";

    private final String wfId;

    public WorkflowUiBuilder(String wfId) {
        this.wfId = wfId;
    }

    /** A stable id for a step's card (e.g. {@code "step:demo:greet"}). */
    public static String stepId(String wfId, String stepRef) {
        return "step:" + wfId + ":" + stepRef;
    }

    /** Renders the whole workflow body as one joined list of step rows. */
    public UiNode render(WorkflowData wf) {
        UiStack body = UiStack.of("wf-body:" + wfId);
        body.withCssClass("wf-body");
        renderSteps(body, wf.getSteps());
        body.child(addStepControl(ROOT));
        return body;
    }

    // -----------------------------------------------------------------------
    // Step list
    // -----------------------------------------------------------------------

    private void renderSteps(UiStack parent, List<StepData> steps) {
        if (steps == null) return;
        for (StepData step : steps) {
            parent.child(renderStep(step));
        }
    }

    private UiNode renderStep(StepData step) {
        String ref = step.getName();
        String id = stepId(wfId, ref);

        // Three buttons per row turned the editor into a wall of controls;
        // the row shows what a step IS, and everything you can do to it
        // waits behind one "…" button.
        UiMenuButton menu = UiMenuButton.of(id + ":menu");
        menu.icon("more");
        menu.item(UiMenuItem.of(id + ":details", "Details").icon("show")
                .onClick(UiTrigger.api("GET", stepUrl(ref, ""))));
        menu.item(UiMenuItem.of(id + ":edit", "Edit").icon("edit")
                .onClick(UiTrigger.api("GET", stepUrl(ref, "edit"))));
        menu.item(UiMenuItem.of(id + ":delete", "Delete").icon("delete")
                .confirm("Delete this step?")
                .onClick(UiTrigger.api("POST", stepUrl(ref, "delete"))));

        UiStack row = UiStack.of(id)
                .direction(UiStack.Direction.HORIZONTAL).gap(10)
                .child(UiText.of(id + ":type", step.getType()).withCssClass("wf-step-type"))
                .child(UiText.of(id + ":name", ref).withCssClass("wf-step-name"))
                .child(menu);
        row.withCssClass("wf-step");

        UiNode nested = renderNested(step, ref);
        if (nested == null) {
            return row;
        }
        UiStack group = UiStack.of(id + ":group").child(row).child(nested);
        group.withCssClass("wf-step-group");
        return group;
    }

    // -----------------------------------------------------------------------
    // Nesting: block / for-each / if
    // -----------------------------------------------------------------------

    private UiNode renderNested(StepData step, String ref) {
        if (step instanceof IfData ifData) {
            return renderIf(ifData, ref);
        }
        if (step instanceof StepContainerData container) { // block, for-each
            String kind = step instanceof BlockData ? "block" : "foreach";
            UiStack inner = indent(ref);
            renderSteps(inner, container.getSteps());
            inner.child(addStepControl(kind + ":" + ref));
            return inner;
        }
        return null;
    }

    /**
     * An if renders one box per condition plus the else, each with its own
     * "add step" control. The mutator creates a missing then/else block on the
     * first insert, so an if whose branches are still empty is editable rather
     * than a dead card you can only delete.
     */
    private UiNode renderIf(IfData ifData, String ref) {
        UiStack branches = indent(ref);
        String id = stepId(wfId, ref);

        IfData.Condition[] conditions = ifData.getConditions();
        if (conditions != null) {
            for (int c = 0; c < conditions.length; c++) {
                IfData.Condition cond = conditions[c];
                String label = "if " + (cond.getCondition() == null ? "" : cond.getCondition());
                branches.child(UiText.of(id + ":cond" + c, label).withCssClass("wf-branch-label"));

                UiStack thenStack = indent(ref + ":then" + c);
                BlockData then = cond.getThenBlock();
                if (then != null) {
                    renderSteps(thenStack, then.getSteps());
                }
                thenStack.child(addStepControl("if:" + ref + ":then:" + c));
                branches.child(thenStack);
            }
        }

        branches.child(UiText.of(id + ":elseLabel", "else").withCssClass("wf-branch-label"));
        UiStack elseStack = indent(ref + ":else");
        if (ifData.getElseBlock() != null) {
            renderSteps(elseStack, ifData.getElseBlock().getSteps());
        }
        elseStack.child(addStepControl("if:" + ref + ":else"));
        branches.child(elseStack);

        return branches;
    }

    // -----------------------------------------------------------------------
    // Actions & helpers
    // -----------------------------------------------------------------------

    /** {@code containerPath} is {@link #ROOT} or the mutator's container DSL. */
    private UiNode addStepControl(String containerPath) {
        // Secondary, not primary: this affordance repeats once per container,
        // and a page of dark full-strength bars is a page shouting at itself.
        return UiAction.secondary("add:" + wfId + ":" + containerPath, "+ Add step")
                .onClick(UiTrigger.api("GET", url("/" + wfId + "/add/" + enc(containerPath))));
    }

    private UiStack indent(String key) {
        UiStack box = UiStack.of("indent:" + wfId + ":" + key);
        box.withCssClass("wf-nested");
        return box;
    }

    private String stepUrl(String stepRef, String op) {
        String base = "/" + wfId + "/step/" + enc(stepRef);
        return url(op.isEmpty() ? base : base + "/" + op);
    }

    /** Step names are user-typed: a space or an umlaut must not break the URL. */
    private static String enc(String segment) {
        return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
    }

    private static String url(String suffix) {
        return "/workflow-admin" + suffix;
    }
}
