package ai.mindconnect.workflow.ui.diagram;

import ai.mindconnect.ui.ext.diagram.UiDiagramNode;
import ai.mindconnect.ui.ext.diagram.UiDiagramShape;

/**
 * Workflow-domain shape vocabulary, expressed in terms of the diagram
 * extension's geometric primitives plus CSS classes. This is the layering
 * boundary: the diagram extension knows only about {@code circle},
 * {@code diamond}, {@code rounded-rect} etc.; "event-start", "decision",
 * "subprocess" are <em>workflow</em> concepts and live here.
 *
 * <p>Each method takes an already-created {@link UiDiagramNode} and applies
 * the appropriate {@code shape} + {@code shapeClass} (+ optional marker /
 * border width). The pattern is:
 * <pre>{@code
 *   var n = UiDiagramNode.of(id, UiDiagramShape.ROUNDED_RECT, label);
 *   WorkflowShapes.decision(n);   // overwrites shape, sets wf-decision class
 * }</pre>
 *
 * <p>The CSS classes ({@code wf-event-start}, {@code wf-decision}, …) are
 * defined in {@code workflow-diagram.css} which the module ships as a
 * static resource at {@code /sui-ext/workflow/workflow-diagram.css}. Hosts
 * that want to render workflow diagrams import that stylesheet alongside
 * the diagram extension.
 */
public final class WorkflowShapes {

    private WorkflowShapes() {}

    // CSS class names — kept as constants so the workflow-diagram.css file
    // and these methods can't drift apart silently.
    public static final String CLASS_EVENT_START = "wf-event-start";
    public static final String CLASS_EVENT_END   = "wf-event-end";
    public static final String CLASS_ACTION      = "wf-action";
    public static final String CLASS_DECISION    = "wf-decision";
    public static final String CLASS_MERGE       = "wf-merge";
    public static final String CLASS_FORK        = "wf-fork";
    public static final String CLASS_JOIN        = "wf-join";
    public static final String CLASS_CALL        = "wf-call";
    public static final String CLASS_SUBPROCESS  = "wf-subprocess";

    // Container variants — share the same geometric primitive (rounded rect)
    // but differ in CSS so each role can have its own visual treatment.
    public static final String CLASS_BRANCH_CONTAINER = "wf-branch-container";
    public static final String CLASS_LOOP_CONTAINER   = "wf-loop-container";
    public static final String CLASS_BLOCK_CONTAINER  = "wf-block-container";

    // -----------------------------------------------------------------------
    // Apply-style helpers — mutate the supplied node in place.
    // -----------------------------------------------------------------------

    /** Filled green circle marking workflow entry. */
    public static UiDiagramNode eventStart(UiDiagramNode n) {
        n.setShape(UiDiagramShape.CIRCLE);
        n.setShapeClass(CLASS_EVENT_START);
        return n;
    }

    /** Thicker-bordered circle marking workflow exit. */
    public static UiDiagramNode eventEnd(UiDiagramNode n) {
        n.setShape(UiDiagramShape.CIRCLE);
        n.setShapeClass(CLASS_EVENT_END);
        n.setBorderWidth(3);
        return n;
    }

    /** Default action / leaf step — rounded rectangle. */
    public static UiDiagramNode action(UiDiagramNode n) {
        n.setShape(UiDiagramShape.ROUNDED_RECT);
        n.setShapeClass(CLASS_ACTION);
        return n;
    }

    /** If / decision diamond with branch labels. */
    public static UiDiagramNode decision(UiDiagramNode n) {
        n.setShape(UiDiagramShape.DIAMOND);
        n.setShapeClass(CLASS_DECISION);
        return n;
    }

    /** Synthetic re-join diamond after a decision. Smaller than a decision. */
    public static UiDiagramNode merge(UiDiagramNode n) {
        n.setShape(UiDiagramShape.DIAMOND);
        n.setShapeClass(CLASS_MERGE);
        return n;
    }

    /** Horizontal bar marking the start of a parallel section. */
    public static UiDiagramNode fork(UiDiagramNode n) {
        n.setShape(UiDiagramShape.BAR);
        n.setShapeClass(CLASS_FORK);
        return n;
    }

    /** Horizontal bar marking the end of a parallel section. */
    public static UiDiagramNode join(UiDiagramNode n) {
        n.setShape(UiDiagramShape.BAR);
        n.setShapeClass(CLASS_JOIN);
        return n;
    }

    /** Invocation of another workflow — rounded rectangle with thick border. */
    public static UiDiagramNode call(UiDiagramNode n) {
        n.setShape(UiDiagramShape.ROUNDED_RECT);
        n.setShapeClass(CLASS_CALL);
        n.setBorderWidth(3);
        return n;
    }

    /** Collapsed sub-process — rounded rectangle with a {@code +} corner badge. */
    public static UiDiagramNode subprocess(UiDiagramNode n) {
        n.setShape(UiDiagramShape.ROUNDED_RECT);
        n.setShapeClass(CLASS_SUBPROCESS);
        n.setMarker("+");
        return n;
    }

    // -----------------------------------------------------------------------
    // Containers — used by BuildContext.runInContainer
    // -----------------------------------------------------------------------

    /** Branch of an If — wraps the steps of one then/else block. The
     *  container-with-header shape pins the condition / "else" label to the
     *  top edge instead of the centre, where the children render. */
    public static UiDiagramNode branchContainer(UiDiagramNode n) {
        n.setShape(UiDiagramShape.CONTAINER_WITH_HEADER);
        n.setShapeClass(CLASS_BRANCH_CONTAINER);
        return n;
    }

    /** Body of a ForEach (parallel or sequential) — wraps the loop's nested steps. */
    public static UiDiagramNode loopContainer(UiDiagramNode n) {
        n.setShape(UiDiagramShape.CONTAINER_WITH_HEADER);
        n.setShapeClass(CLASS_LOOP_CONTAINER);
        return n;
    }

    /** Generic named block — wraps a sequence of steps with no extra semantics. */
    public static UiDiagramNode blockContainer(UiDiagramNode n) {
        n.setShape(UiDiagramShape.CONTAINER_WITH_HEADER);
        n.setShapeClass(CLASS_BLOCK_CONTAINER);
        return n;
    }
}
