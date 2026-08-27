package ai.mindconnect.workflow.step.form;

import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.workflow.domain.HaltData;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A halt that shows a form.
 *
 * <p>It suspends the workflow exactly like a {@link HaltData} — same
 * pause/resume, same snapshot, same "waits for input" story — but it carries the
 * form to show while it waits: a semantic-ui {@link UiNode}. The run view renders
 * that node, and the fields the user fills become the params the workflow resumes
 * with.
 *
 * <p>This is why it lives in its own module rather than in the engine. A
 * {@code UiNode} is a semantic-ui type, and {@code mc-workflow} is deliberately
 * free of any UI dependency. Here — a module that already depends on both — the
 * step can hold the form directly. The engine never sees it: to the engine this
 * is just a halt (it extends {@link HaltData}, so {@code pendingHalt} and the
 * whole resume path treat it as one), and only the admin, which knows
 * semantic-ui, reads {@link #form} and paints it.
 *
 * <p>Because {@code UiNode} carries its own Jackson type info, the form
 * round-trips through the workflow serializer with no extra wiring.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FormStepData extends HaltData {

    /**
     * The form to render while suspended — typically a {@code UiForm} with the
     * fields to collect. The admin appends the "Continue" action itself (it is
     * the only side that knows the resume URL), so an author supplies just the
     * fields, not the submit.
     */
    private UiNode form;

    /**
     * Alternative to {@link #form}: the name of a workflow variable holding
     * the form to render — built at runtime (typically by a JavaScript step)
     * as a UiNode-shaped structure ({@code {"type":"form","id":...,"fields":[...]}}),
     * either as a map or a JSON string. This is how a workflow shows forms
     * whose fields depend on runtime data — one checkbox per extracted
     * document section, one textarea per generated chapter. When both are
     * set, {@code formFrom} wins.
     */
    private String formFrom;
}
