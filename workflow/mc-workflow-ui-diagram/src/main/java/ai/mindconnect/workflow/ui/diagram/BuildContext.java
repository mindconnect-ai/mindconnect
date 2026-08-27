package ai.mindconnect.workflow.ui.diagram;

import ai.mindconnect.ui.ext.diagram.Position;
import ai.mindconnect.ui.ext.diagram.UiDiagram;
import ai.mindconnect.ui.ext.diagram.UiDiagramEdge;
import ai.mindconnect.ui.ext.diagram.UiDiagramNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Mutable scratchpad threaded through the recursive build. Owns id counters,
 * the duplicate-name guard, and a stack of "current containers" — the latter
 * tracks which node any newly-added child should be hung under so the builder
 * can stay focused on dispatch and not on tree-management plumbing.
 *
 * <p>Layout strategy: naïve vertical stack within whatever container is
 * active. Top-level (no active container) lays children out along
 * {@link #TRUNK_X} starting at {@code y = 40}; inside a container the
 * cursor resets to {@code (CONTAINER_PADDING, label gutter)} so children's
 * positions are relative to the container's top-left corner. Container size
 * is computed after the body runs by measuring the children's extents.
 */
class BuildContext {

    /** Top-level trunk column. Containers reset their own internal cursor. */
    static final double TRUNK_X = 240;

    /** Vertical gap between consecutive steps in the same column. */
    static final double ROW_SPACING = 100;

    /** Padding kept around children inside a container, on every side. */
    static final double CONTAINER_PADDING = 24;

    /** Extra top space inside a container for its own label / title. */
    static final double CONTAINER_LABEL_GUTTER = 20;

    /**
     * One frame on the container stack — captures everything needed to lay
     * children out in the active container's local coordinate system. Top
     * of the stack is the active container.
     */
    private static final class Frame {
        /** Where children get appended. {@code null} means top-level (diagram.nodes). */
        final UiDiagramNode container;
        /** x to use for the next child added at this level. */
        final double trunkX;
        /** y cursor for the next child at this level. */
        double cursorY;
        Frame(UiDiagramNode container, double trunkX, double cursorY) {
            this.container = container;
            this.trunkX = trunkX;
            this.cursorY = cursorY;
        }
    }

    private final UiDiagram diagram;
    private final Set<String> seenStepNames = new HashSet<>();
    private final Deque<Frame> frames = new ArrayDeque<>();
    /** nodeId -> parentNodeId; null = top-level. Maintained by {@link #attach}.
     *  Used by {@link #lcaOf} to pick the container that "owns" each edge. */
    private final java.util.Map<String, String> parentOf = new java.util.HashMap<>();
    private int nodeIdSeq = 0;
    private int edgeIdSeq = 0;
    private int autoStepSeq = 0;
    /**
     * Extra tail nodes that should be wired into the next leaf step's head.
     * Used by If-without-merge: each branch-container becomes a separate
     * predecessor, plus optionally the decision itself for the implicit
     * "else" path. The list is consumed (cleared) by the next leaf append.
     */
    private final java.util.List<PendingEdge> extraIncomingEdges = new java.util.ArrayList<>();

    /** One pending incoming edge: from a tail, optionally labelled. */
    record PendingEdge(UiDiagramNode from, String label) {}

    BuildContext(UiDiagram diagram) {
        this.diagram = diagram;
        // Root frame — children go straight into diagram.nodes, trunk-column
        // layout starting at (TRUNK_X, 40).
        frames.push(new Frame(null, TRUNK_X, 40));
    }

    UiDiagram diagram() {
        return diagram;
    }

    // ── ID allocation ──────────────────────────────────────────────────────

    String nextNodeId() {
        return "n" + (++nodeIdSeq);
    }

    String nextEdgeId() {
        return "e" + (++edgeIdSeq);
    }

    /**
     * Ensures the supplied step name is non-blank and unique within the
     * workflow. Returns the (possibly auto-generated) name; the caller is
     * expected to write it back into the StepData.
     *
     * <p>Auto-generated names follow the {@code step_N} pattern, where {@code N}
     * is chosen to be larger than any {@code step_*} name already seen in the
     * workflow. That matters because builds are stateless: every build starts
     * from scratch, walks the steps left-to-right, and assigns names as it
     * goes. Without the "step past existing" logic, the very first
     * still-blank step would get {@code step_1} again — which collides with
     * the {@code step_1} that an earlier build's auto-naming committed back
     * into the workflow.
     */
    String reserveStepName(String existing) {
        if (existing != null && !existing.isBlank()) {
            if (!seenStepNames.add(existing)) {
                throw new DuplicateStepNameException(existing);
            }
            // Keep the auto-seq watermark above any user-or-previously-auto-named
            // step_N we encounter, so subsequent auto-names don't collide.
            bumpAutoSeqToMatch(existing);
            return existing;
        }
        // Probe upward until we land on a name nobody's taken yet.
        String name;
        do {
            name = "step_" + (++autoStepSeq);
        } while (!seenStepNames.add(name));
        return name;
    }

    /**
     * If {@code name} matches the {@code step_N} pattern, advances the
     * auto-sequence counter so it won't re-emit that number. Silently no-ops
     * for any other name. The counter only ever moves forward.
     */
    private void bumpAutoSeqToMatch(String name) {
        if (!name.startsWith("step_")) return;
        try {
            int n = Integer.parseInt(name.substring("step_".length()));
            if (n > autoStepSeq) autoStepSeq = n;
        } catch (NumberFormatException ignored) {
            // step_foo and the like — not part of the auto-name space.
        }
    }

    /**
     * Pre-seeds the name counter from every non-blank name already present
     * in the workflow. After this call, {@link #reserveStepName} will never
     * generate a name that collides with an existing one — auto-generated
     * names start strictly above the highest existing {@code step_N}.
     *
     * <p>Note this does <em>not</em> add the names to {@link #seenStepNames};
     * that's still {@link #reserveStepName}'s job, so it can detect genuine
     * user-supplied duplicates and raise {@link DuplicateStepNameException}.
     */
    void seedAutoSeqFrom(java.util.Collection<String> existingNames) {
        for (String n : existingNames) {
            if (n != null && !n.isBlank()) {
                bumpAutoSeqToMatch(n);
            }
        }
    }

    // ── Layout cursor (active frame) ───────────────────────────────────────

    double cursorY() {
        return frames.peek().cursorY;
    }

    void advanceCursor(double dy) {
        frames.peek().cursorY += dy;
    }

    /** Resets the active frame's cursor to {@code y} — used when laying out
     *  parallel branches that should start at the same vertical position. */
    void setCursorY(double y) {
        frames.peek().cursorY = y;
    }

    /** x-coordinate to use for nodes on the active container's trunk. */
    double trunkX() {
        return frames.peek().trunkX;
    }

    /**
     * Sum of the active frame-stack's container positions — i.e. how far
     * we currently are from the diagram's absolute (0, 0). Useful for
     * post-processing routes (waypoints, etc.) where the edge model wants
     * absolute coordinates but the local nodes were positioned in their
     * container's local frame.
     */
    double[] activeFrameAbsoluteOrigin() {
        double ax = 0, ay = 0;
        // Frame.container.position is itself in the *parent* container's
        // local coords, so we have to walk the whole stack.
        // We iterate bottom-to-top so each container's position gets added
        // against the running ancestor offset. The top-level frame has
        // container == null and contributes nothing.
        var stack = new java.util.ArrayList<>(frames);
        java.util.Collections.reverse(stack);  // bottom (root) first
        for (Frame f : stack) {
            if (f.container == null) continue;
            var p = f.container.getPosition();
            if (p == null) continue;
            ax += p.getX();
            ay += p.getY();
        }
        return new double[] { ax, ay };
    }

    // ── Node + edge emission ───────────────────────────────────────────────

    /**
     * Adds a synthetic node (no backing step) at the active frame's trunk
     * column. Position is interpreted in the active container's local
     * coordinate system (or absolute for top-level frames). The shape
     * decorator picks the workflow role.
     */
    UiDiagramNode addSyntheticNode(Consumer<UiDiagramNode> shapeDecorator, String label) {
        var n = new UiDiagramNode();
        n.setId(nextNodeId());
        n.setLabel(label);
        n.setSynthetic(true);
        n.setPosition(Position.of(trunkX(), cursorY()));
        shapeDecorator.accept(n);
        attach(n);
        return n;
    }

    /** As {@link #addSyntheticNode} but for a step-backed node (carries stepRef). */
    UiDiagramNode addStepNode(Consumer<UiDiagramNode> shapeDecorator,
                              String label, String stepRef, String stepType) {
        var n = new UiDiagramNode();
        n.setId(nextNodeId());
        n.setLabel(label);
        n.setStepRef(stepRef);
        n.put("stepType", stepType);
        n.setPosition(Position.of(trunkX(), cursorY()));
        shapeDecorator.accept(n);
        attach(n);
        return n;
    }

    /**
     * Attaches a node to the active container, or to the diagram if we're at
     * top level. Edges always live at top level so they can cross container
     * boundaries — that's a property of the wire format and {@link #addFlowEdge}
     * / {@link #addStructuralEdge} just write into {@code diagram.getEdges()}.
     */
    private void attach(UiDiagramNode n) {
        Frame frame = frames.peek();
        if (frame.container == null) {
            diagram.addNode(n);
            parentOf.put(n.getId(), null);
        } else {
            frame.container.addChild(n);
            parentOf.put(n.getId(), frame.container.getId());
        }
    }

    UiDiagramEdge addFlowEdge(UiDiagramNode from, UiDiagramNode to) {
        var e = UiDiagramEdge.flow(nextEdgeId(), from.getId(), to.getId());
        e.setOwnerNodeId(lcaOf(from.getId(), to.getId()));
        diagram.addEdge(e);
        return e;
    }

    UiDiagramEdge addStructuralEdge(UiDiagramNode from, UiDiagramNode to, String label) {
        var e = UiDiagramEdge.structural(nextEdgeId(), from.getId(), to.getId(), label);
        e.setOwnerNodeId(lcaOf(from.getId(), to.getId()));
        diagram.addEdge(e);
        return e;
    }

    /**
     * Lowest common ancestor of {@code a} and {@code b} in the container
     * tree, by node id. {@code null} means the LCA is the diagram itself
     * (a top-level frame). Used to pick the SVG group that owns an edge.
     */
    private String lcaOf(String a, String b) {
        var ancestorsOfA = new java.util.HashSet<String>();
        String cur = a;
        while (cur != null) { ancestorsOfA.add(cur); cur = parentOf.get(cur); }
        ancestorsOfA.add(null);    // top-level sentinel
        cur = b;
        while (cur != null) {
            if (ancestorsOfA.contains(cur)) {
                // The edge's owner is a *container* that holds both
                // endpoints. If a == b's parent (or vice versa) we still
                // want the parent — i.e. don't return a or b themselves
                // unless one of them IS a container holding the other.
                // For our use-case (edges connect siblings or cross
                // boundaries), the first matching ancestor walked from
                // b is always the LCA-as-container.
                return cur;
            }
            cur = parentOf.get(cur);
        }
        return null;   // both ended up at top-level
    }

    /**
     * Queues an extra incoming edge to attach to the <em>next</em> step
     * appended on this trunk. Used by If: each branch tail and (optionally,
     * if there's no else) the decision itself gets queued, so that when the
     * next leaf step arrives all branches merge into it without a synthetic
     * merge diamond in between.
     *
     * <p>{@link #takePendingIncoming} drains and returns the queue; the
     * builder calls it from each {@code append…} helper before wiring its
     * "main" incoming edge.
     */
    void queueIncomingEdge(UiDiagramNode from, String label) {
        extraIncomingEdges.add(new PendingEdge(from, label));
    }

    /**
     * Returns and clears the list of pending incoming edges queued by
     * earlier construct emitters (typically If). Each entry will be drawn
     * by the next leaf-step append in addition to its normal {@code prev}
     * edge.
     */
    java.util.List<PendingEdge> takePendingIncoming() {
        if (extraIncomingEdges.isEmpty()) return java.util.List.of();
        var snapshot = new java.util.ArrayList<>(extraIncomingEdges);
        extraIncomingEdges.clear();
        return snapshot;
    }

    boolean hasPendingIncoming() {
        return !extraIncomingEdges.isEmpty();
    }

    // ── Container layout ───────────────────────────────────────────────────

    /**
     * Creates a container at the active frame's trunk column, runs {@code body}
     * with the container pushed onto the frame stack (so any nodes the body
     * adds become its children at relative positions), then measures the
     * children's bounding box to size the container.
     *
     * <p>After the body returns, the active frame's cursor is advanced past
     * the container so subsequent siblings stack below the whole sub-tree.
     */
    UiDiagramNode runInContainer(Consumer<UiDiagramNode> shapeDecorator,
                                 String label,
                                 String stepRef,
                                 String stepType,
                                 Runnable body) {
        return runInContainerAt(shapeDecorator, label, stepRef, stepType, 0, body);
    }

    /**
     * Variant of {@link #runInContainer} that horizontally offsets the
     * container from the active frame's trunk x. Used when laying out
     * sibling containers side-by-side (decision branches): the caller emits
     * branch 1 at offset 0, then branch 2 at offset {@code BRANCH_OFFSET},
     * with the cursor reset between calls so both start at the same y.
     */
    UiDiagramNode runInContainerAt(Consumer<UiDiagramNode> shapeDecorator,
                                   String label,
                                   String stepRef,
                                   String stepType,
                                   double xOffset,
                                   Runnable body) {
        double containerX = trunkX() + xOffset;
        double containerY = cursorY();

        var container = new UiDiagramNode();
        container.setId(nextNodeId());
        container.setLabel(label);
        container.setPosition(Position.of(containerX, containerY));
        if (stepRef == null) {
            container.setSynthetic(true);
        } else {
            container.setStepRef(stepRef);
            container.put("stepType", stepType);
        }
        shapeDecorator.accept(container);
        attach(container);

        // Push a new frame. Inside the container, children's positions are
        // relative to the container's own top-left, so the trunk x and y
        // start near the container's interior origin.
        frames.push(new Frame(container, CONTAINER_PADDING, CONTAINER_LABEL_GUTTER + CONTAINER_PADDING));
        body.run();
        Frame inner = frames.pop();

        // If any child has a local X (or Y) past the padding line on the
        // negative side — typically a nested If whose left branch fanned
        // out before the trunk column — the container has to grow that way
        // too. We shift the container's own position by the overflow and
        // compensate every child by the same delta so nothing moves
        // visually; the result is a wider container that fully envelops
        // its contents.
        double minLeft = CONTAINER_PADDING, minTop = CONTAINER_LABEL_GUTTER + CONTAINER_PADDING;
        if (container.getChildren() != null) {
            for (UiDiagramNode child : container.getChildren()) {
                if (child.getPosition() == null) continue;
                minLeft = Math.min(minLeft, child.getPosition().getX());
                minTop  = Math.min(minTop,  child.getPosition().getY());
            }
        }
        double shiftX = CONTAINER_PADDING - minLeft;
        double shiftY = (CONTAINER_LABEL_GUTTER + CONTAINER_PADDING) - minTop;
        if (shiftX > 0 || shiftY > 0) {
            // Move the container itself the opposite direction so children
            // stay in place on screen. Container.position is in the parent
            // container's local coords, so this is a parent-frame shift.
            container.setPosition(Position.of(
                container.getPosition().getX() - shiftX,
                container.getPosition().getY() - shiftY));
            if (container.getChildren() != null) {
                for (UiDiagramNode child : container.getChildren()) {
                    if (child.getPosition() == null) continue;
                    child.setPosition(Position.of(
                        child.getPosition().getX() + shiftX,
                        child.getPosition().getY() + shiftY));
                }
            }
            // Edges owned by this container have waypoints in this
            // container's local frame, so they need the same shift —
            // otherwise the route ends up "behind" the moved children.
            String containerId = container.getId();
            for (UiDiagramEdge edge : diagram.getEdges()) {
                if (!containerId.equals(edge.getOwnerNodeId())) continue;
                if (edge.getWaypoints() == null) continue;
                for (Position wp : edge.getWaypoints()) {
                    wp.setX(wp.getX() + shiftX);
                    wp.setY(wp.getY() + shiftY);
                }
            }
        }

        // Size the container to fit its (possibly shifted) children.
        double maxRight = CONTAINER_PADDING + 160;   // sensible minimum
        double maxBottom = CONTAINER_LABEL_GUTTER + CONTAINER_PADDING + 40;
        if (container.getChildren() != null) {
            for (UiDiagramNode child : container.getChildren()) {
                if (child.getPosition() == null) continue;
                double cw = child.getWidth()  != null ? child.getWidth()  : 160;
                double ch = child.getHeight() != null ? child.getHeight() : ROW_SPACING - 20;
                maxRight  = Math.max(maxRight, child.getPosition().getX() + cw);
                maxBottom = Math.max(maxBottom, child.getPosition().getY() + ch);
            }
        }
        container.setWidth((int)  Math.round(maxRight + CONTAINER_PADDING));
        container.setHeight((int) Math.round(maxBottom + CONTAINER_PADDING));

        // Advance the active frame's cursor past the freshly-laid-out container.
        advanceCursor(container.getHeight() + 20);

        // (inner is discarded — only the container itself and its tree of
        // children matter from here on.)
        return container;
    }
}
