package ai.mindconnect.workflow.ui.diagram;

import ai.mindconnect.ui.ext.diagram.UiDiagram;
import ai.mindconnect.ui.ext.diagram.UiDiagramNode;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.BlockData;
import ai.mindconnect.workflow.domain.CallWorkflowData;
import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.JumpToData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.ui.ext.diagram.Position;
import ai.mindconnect.ui.ext.diagram.UiDiagramEdge;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Converts a {@link WorkflowData} tree into a {@link UiDiagram} suitable for
 * the SVG renderer in {@code mc-semantic-ui-ext-diagram}.
 *
 * <h2>Layout</h2>
 * Etappe 1a uses a deliberately naïve vertical layout: every step stacks
 * below the previous one along a fixed trunk column, and branching steps
 * (If, ForEach) lay their interiors out to the left/right of that trunk.
 * The result is topologically correct but visually crowded for any
 * non-trivial workflow. Etappe 1b replaces this with eclipse-elk's layered
 * algorithm; the rest of the pipeline (wire model, builder dispatch, client
 * renderer) stays the same.
 *
 * <h2>Name canonicalisation</h2>
 * The builder mutates the {@link WorkflowData} it processes: every step's
 * {@code name} is filled in (or replaced if blank) so that
 * {@link UiDiagramNode#getStepRef()} is a stable, unique handle into the
 * workflow. Duplicate user-supplied names raise
 * {@link DuplicateStepNameException}. Callers that don't want the input
 * mutated should pass a deep copy.
 *
 * <h2>Synthetic nodes</h2>
 * Start, end, decision-merge, fork, and join markers are emitted with
 * {@code synthetic = true}. The editor must treat them as non-deletable —
 * they vanish automatically when the underlying structural step changes.
 */
public class WorkflowDiagramBuilder {

    /**
     * Reserved stepRef for the synthetic start marker. Lets the editor
     * persist a drag-position for it through the same LayoutStore path
     * used for real steps — these refs would never collide with a real
     * step name because the double-underscore prefix is reserved.
     */
    public static final String SYNTHETIC_START_REF = "__start__";
    public static final String SYNTHETIC_END_REF   = "__end__";
    /** Prefix for synthetic-but-draggable nodes whose position is persisted
     *  under a stable, computed stepRef. Branches use {@code __branch__:<path>}
     *  and the If's join uses {@code __merge__:<ifName>}. */
    public static final String SYNTHETIC_MERGE_PREFIX  = "__merge__:";
    public static final String SYNTHETIC_BRANCH_PREFIX = "__branch__:";

    /**
     * Builds an activity-style diagram for the supplied workflow. The
     * workflow is mutated in place: blank step names get auto-generated
     * values so {@code stepRef} is unique.
     *
     * @throws DuplicateStepNameException if two user-supplied step names collide
     */
    public UiDiagram build(WorkflowData wf) {
        return build(wf, Map.of(), Map.of());
    }

    public UiDiagram build(WorkflowData wf, Map<String, Position> layoutOverrides) {
        return build(wf, layoutOverrides, Map.of());
    }

    /**
     * Builds the diagram and then overlays user-supplied positions. Any
     * non-synthetic node whose {@code stepRef} appears in {@code layoutOverrides}
     * gets its position replaced; nodes without an override keep the naïve
     * stack-layout coordinates the builder assigned by default.
     *
     * <p>The override map keys are step names (the same handles the editor
     * uses for mutations), so the editor can pass back exactly what it stored
     * when the user dragged a node — without translating between domain refs
     * and diagram node IDs.
     *
     * <p>Edges have no positions to override; the renderer always draws them
     * from node centre to node centre. Synthetic nodes (start, end, merge,
     * fork, join) are also not user-positionable in Phase 3a — those are
     * scaffolding and follow the structural steps they belong to.
     *
     * @param layoutOverrides {@code stepRef → position}; may be empty but not null
     */
    /**
     * Full builder entry-point.
     *
     * @param layoutOverrides  {@code stepRef → relative position}; replaces
     *                         the default stack-layout position for each
     *                         matching node. Empty map for "no overrides".
     * @param edgeWaypoints    {@code (sourceStepRef + "->" + targetStepRef)
     *                         → list of absolute waypoint positions}.
     *                         Identifies edges by their step-anchored endpoints
     *                         (stable across rebuilds even though numeric edge
     *                         IDs are reassigned). Empty map for straight edges.
     */
    public UiDiagram build(WorkflowData wf,
                           Map<String, Position> layoutOverrides,
                           Map<String, List<Position>> edgeWaypoints) {
        var diagram = new UiDiagram();
        diagram.setId(diagramIdFor(wf));
        diagram.setTitle(wf.getName());

        var ctx = new BuildContext(diagram);

        // Pre-seed the auto-name counter from every name already in the
        // workflow so freshly-blank steps don't collide with previously
        // auto-named ones. Without this, build → insert → build produces
        // a duplicate step_1 because the counter resets each build.
        var existingNames = new java.util.ArrayList<String>();
        collectStepNames(wf.getSteps(), existingNames);
        ctx.seedAutoSeqFrom(existingNames);

        // Start marker — synthetic (no backing StepData), but carries a
        // reserved stepRef so the editor can persist drag positions for it
        // through the same LayoutStore path used for regular steps.
        var startNode = ctx.addSyntheticNode(WorkflowShapes::eventStart, "start");
        startNode.setStepRef(SYNTHETIC_START_REF);
        // Start: user can grab it to reposition (drag), prepend a step
        // (insert-after with __start__ means "before the first step"), but
        // can't be deleted — the workflow always begins.
        startNode.setCanDelete(false);
        ctx.advanceCursor(BuildContext.ROW_SPACING);

        UiDiagramNode tail = appendSteps(wf.getSteps(), startNode, ctx);

        var endNode = ctx.addSyntheticNode(WorkflowShapes::eventEnd, "end");
        endNode.setStepRef(SYNTHETIC_END_REF);
        // End: same lifecycle as start but no "insert after the end" gesture.
        endNode.setCanDelete(false);
        endNode.setCanInsertAfter(false);
        // If the workflow's last step was an If, its branch-tails are queued
        // as pending incoming edges. wireIncomingEdges consumes them so they
        // feed the end marker directly — no synthetic merge in between.
        wireIncomingEdges(tail, endNode, ctx);

        // Wire JumpTo arrows. The target step might appear anywhere in the
        // workflow — forward, backward, inside a container — so we resolve
        // it by stepRef *after* every node has been emitted. The result is
        // one extra structural edge per JumpTo with a non-empty target.
        wireJumpToEdges(diagram, ctx);

        // Apply manual positions on top of the default layout. With the
        // nested model an override replaces a node's *position relative to
        // its parent* — same key (stepRef), different geometric semantics,
        // but indistinguishable from the editor's point of view because a
        // top-level node's "relative" position is just its absolute one.
        if (!layoutOverrides.isEmpty()) {
            applyOverrides(diagram.getNodes(), layoutOverrides);
        }

        // Apply edge waypoints by looking up each edge's (source-stepRef,
        // target-stepRef) — we resolve the node ids back to their stepRefs
        // since the overrides are keyed by step name (stable across builds).
        if (!edgeWaypoints.isEmpty()) {
            applyEdgeWaypoints(diagram, edgeWaypoints);
        }

        // Canvas extent — sweep the whole tree to find the maximum extent
        // and pad. Done after override-application so user drags can grow
        // the canvas, not just shift content within it.
        double[] extent = new double[] { BuildContext.TRUNK_X + 200, ctx.cursorY() + 80 };
        for (UiDiagramNode n : diagram.getNodes()) {
            walkExtent(n, 0, 0, extent);
        }
        diagram.setWidth((int) Math.round(extent[0]));
        diagram.setHeight((int) Math.round(extent[1]));
        return diagram;
    }

    private void applyOverrides(List<UiDiagramNode> nodes, Map<String, Position> overrides) {
        if (nodes == null) return;
        for (UiDiagramNode n : nodes) {
            if (n.getStepRef() != null) {
                Position p = overrides.get(n.getStepRef());
                if (p != null) n.setPosition(p);
            }
            applyOverrides(n.getChildren(), overrides);
        }
    }

    /**
     * Walks every edge in the diagram and attaches the corresponding
     * waypoint list from {@code edgeWaypoints} if one exists. Edge identity
     * is the (sourceStepRef -> targetStepRef) pair — we resolve each edge's
     * node IDs back to stepRefs via the node tree.
     */
    private void applyEdgeWaypoints(UiDiagram diagram,
                                    Map<String, List<Position>> edgeWaypoints) {
        var stepRefById = new java.util.HashMap<String, String>();
        collectStepRefById(diagram.getNodes(), stepRefById);
        for (var edge : diagram.getEdges()) {
            String srcRef = stepRefById.get(edge.getSource());
            String dstRef = stepRefById.get(edge.getTarget());
            if (srcRef == null || dstRef == null) continue;
            var wps = edgeWaypoints.get(srcRef + "->" + dstRef);
            if (wps != null && !wps.isEmpty()) {
                edge.setWaypoints(wps);
            }
        }
    }

    private void collectStepRefById(List<UiDiagramNode> nodes, Map<String, String> out) {
        if (nodes == null) return;
        for (UiDiagramNode n : nodes) {
            if (n.getStepRef() != null) out.put(n.getId(), n.getStepRef());
            collectStepRefById(n.getChildren(), out);
        }
    }

    /**
     * Walks the freshly-built diagram and, for every node whose step is a
     * {@code JumpToData} with a non-empty target, draws a structural edge
     * to the target node. The target is resolved by {@code stepRef} so
     * it works regardless of where the target lives in the container
     * tree (a JumpTo inside an If can point at a step in a sibling
     * ForEach, etc.). Unknown targets are silently ignored — the side
     * panel will surface the broken reference instead.
     */
    private void wireJumpToEdges(UiDiagram diagram, BuildContext ctx) {
        // index: stepName -> node id (for every step-backed node)
        var nodeByStepRef = new java.util.HashMap<String, UiDiagramNode>();
        collectStepRefNodes(diagram.getNodes(), nodeByStepRef);
        // walk the source workflow tree to find JumpToData carriers.
        for (var entry : nodeByStepRef.entrySet()) {
            var srcNode = entry.getValue();
            var stepType = srcNode.getData() == null ? null : srcNode.getData().get("stepType");
            if (!"jumpto".equals(stepType)) continue;
            String target = srcNode.getData().get("jumpToTarget");
            if (target == null || target.isBlank()) continue;
            UiDiagramNode dst = nodeByStepRef.get(target);
            if (dst == null) continue;
            // Structural so the user can't accidentally delete the jump
            // edge from the canvas — the only way to remove it is via the
            // side panel (clearing or changing the JumpTo's target).
            ctx.addStructuralEdge(srcNode, dst, "jump");
        }
    }

    private void collectStepRefNodes(List<UiDiagramNode> nodes, Map<String, UiDiagramNode> out) {
        if (nodes == null) return;
        for (UiDiagramNode n : nodes) {
            if (n.getStepRef() != null) out.put(n.getStepRef(), n);
            collectStepRefNodes(n.getChildren(), out);
        }
    }

    private void walkExtent(UiDiagramNode n, double parentAbsX, double parentAbsY, double[] extent) {
        if (n.getPosition() == null) return;
        double absX = parentAbsX + n.getPosition().getX();
        double absY = parentAbsY + n.getPosition().getY();
        double w = n.getWidth()  != null ? n.getWidth()  : 200;
        double h = n.getHeight() != null ? n.getHeight() : 120;
        extent[0] = Math.max(extent[0], absX + w + 40);
        extent[1] = Math.max(extent[1], absY + h + 40);
        if (n.getChildren() != null) {
            for (UiDiagramNode c : n.getChildren()) {
                walkExtent(c, absX, absY, extent);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Sequencing
    // -----------------------------------------------------------------------

    /**
     * Lays out a list of steps in sequence, wiring each step's tail into the
     * next step's head. Returns the tail of the last step (or {@code prev}
     * if the list is empty).
     */
    private UiDiagramNode appendSteps(List<StepData> steps, UiDiagramNode prev, BuildContext ctx) {
        UiDiagramNode current = prev;
        for (StepData step : steps) {
            current = appendStep(step, current, ctx);
        }
        return current;
    }

    /** Dispatches one step to its renderer and returns the tail node. */
    private UiDiagramNode appendStep(StepData step, UiDiagramNode prev, BuildContext ctx) {
        // Branching constructs handle their own naming so they can label the
        // branch edges sensibly. Leaf steps go through the common helper.
        if (step instanceof IfData ifStep) {
            return appendIf(ifStep, prev, ctx);
        }
        if (step instanceof ForEachData fe) {
            return appendForEach(fe, prev, ctx);
        }
        if (step instanceof BlockData block) {
            return appendBlock(block, prev, ctx);
        }

        // Leaf step — single node on the trunk.
        return appendLeafStep(step, prev, ctx);
    }

    private UiDiagramNode appendLeafStep(StepData step, UiDiagramNode prev, BuildContext ctx) {
        String name = ctx.reserveStepName(step.getName());
        step.setName(name);

        var node = ctx.addStepNode(shapeFor(step), labelFor(step, name), name, step.getType());
        ctx.advanceCursor(BuildContext.ROW_SPACING);

        // Stash JumpTo's target on the node so the post-build pass can
        // look up the destination without having to walk the workflow
        // tree again. Empty string when the jump hasn't been wired yet —
        // the post-build pass treats that the same as null and skips the
        // edge, but the side panel can still surface "needs a target".
        if (step instanceof JumpToData jump) {
            node.put("jumpToTarget", jump.getJumpTo() == null ? "" : jump.getJumpTo());
        }

        wireIncomingEdges(prev, node, ctx);
        return node;
    }

    /**
     * Wires the incoming edges of {@code node}. If the build context has any
     * pending incoming edges queued (an If just finished and parked its
     * branch-tails for the next step to pick up), those replace the normal
     * "single edge from prev" pattern. Otherwise, a regular flow edge from
     * {@code prev} is drawn.
     */
    private void wireIncomingEdges(UiDiagramNode prev, UiDiagramNode node, BuildContext ctx) {
        if (ctx.hasPendingIncoming()) {
            for (var p : ctx.takePendingIncoming()) {
                if (p.label() != null) {
                    ctx.addStructuralEdge(p.from(), node, p.label());
                } else {
                    ctx.addFlowEdge(p.from(), node);
                }
            }
        } else if (prev != null) {
            // prev == null only on the *first* step of a container body —
            // the container's own incoming edge already carries the "how we
            // got here" semantics, so we deliberately draw no extra flow
            // edge here.
            ctx.addFlowEdge(prev, node);
        }
    }

    // -----------------------------------------------------------------------
    // Branching: If
    // -----------------------------------------------------------------------

    /**
     * Renders an {@code IfData} BPMN-style: a square decision diamond, the
     * branches as free-standing containers fanning out around the trunk,
     * and a synthetic merge gateway (smaller diamond) that gathers every
     * branch's tail back onto the trunk before the next step. The merge is
     * purely visual — no StepData behind it — so the user can't click,
     * rename, or delete it (same lifecycle as the start/end markers).
     */
    private UiDiagramNode appendIf(IfData step, UiDiagramNode prev, BuildContext ctx) {
        String name = ctx.reserveStepName(step.getName());
        step.setName(name);

        var decision = ctx.addStepNode(WorkflowShapes::decision, labelFor(step, name),
            name, step.getType());
        // The If's own diamond accepts select + drag + delete (the whole
        // construct goes away with the diamond), but its insert-after
        // gesture lives on the merge below — a step "after the diamond
        // but before the branches" doesn't make sense.
        decision.setCanInsertAfter(false);
        ctx.advanceCursor(BuildContext.ROW_SPACING);
        wireIncomingEdges(prev, decision, ctx);

        // Each branch becomes a synthetic container holding its steps, laid
        // out side-by-side around the trunk column.
        var branchContainers = new ArrayList<UiDiagramNode>();
        double branchStartY = ctx.cursorY();
        double maxBranchBottom = branchStartY;

        // Branch x-offsets fan out around the trunk.
        final double BRANCH_SPACING = 280;
        final double FIRST_BRANCH_OFFSET = -BRANCH_SPACING / 2;

        var conditions = step.getConditions();
        int branchIndex = 0;
        if (conditions != null) {
            for (IfData.Condition cond : conditions) {
                ctx.setCursorY(branchStartY);
                double xOffset = FIRST_BRANCH_OFFSET + branchIndex * BRANCH_SPACING;
                String containerPath = "if:" + name + ":then:" + branchIndex;
                UiDiagramNode branch = layoutBranchContainer(
                    cond.getThenBlock(), decision, cond.getCondition(), xOffset,
                    containerPath, ctx);
                branchContainers.add(branch);
                maxBranchBottom = Math.max(maxBranchBottom, ctx.cursorY());
                branchIndex++;
            }
        }
        boolean hasElse = step.getElseBlock() != null;
        if (hasElse) {
            ctx.setCursorY(branchStartY);
            double xOffset = FIRST_BRANCH_OFFSET + branchIndex * BRANCH_SPACING;
            UiDiagramNode elseBranch = layoutBranchContainer(
                step.getElseBlock(), decision, "else", xOffset,
                "if:" + name + ":else", ctx);
            branchContainers.add(elseBranch);
            maxBranchBottom = Math.max(maxBranchBottom, ctx.cursorY());
        }

        // Park the cursor on the trunk line below the deepest branch, then
        // drop a synthetic merge diamond there. Every branch's tail flows
        // into it, and the merge is what the caller continues from — the
        // workflow's main column stays a single line again from here on.
        ctx.setCursorY(maxBranchBottom);
        var merge = ctx.addSyntheticNode(WorkflowShapes::merge, null);
        // Reserve-prefix stepRef so the join can be dragged like any other
        // node and its position survives a round-trip through the server's
        // LayoutStore. No StepData backs it: not deletable, not selectable
        // (the side panel has nothing to show for it). The insert-after
        // gesture is on though — clicking the merge's plus means "insert a
        // step after the whole If", which the host translates back into
        // afterStepRef = the If's own name.
        merge.setStepRef(SYNTHETIC_MERGE_PREFIX + name);
        merge.setCanSelect(false);
        merge.setCanDelete(false);
        ctx.advanceCursor(BuildContext.ROW_SPACING);

        if (branchContainers.isEmpty()) {
            // Degenerate: an If with no branches — connect the decision
            // straight into the merge so the workflow stays wired.
            ctx.addFlowEdge(decision, merge);
        } else {
            for (UiDiagramNode container : branchContainers) {
                ctx.addFlowEdge(container, merge);
            }
            // No explicit else block → the "no condition matched" path
            // bypasses the branches and goes straight from decision to
            // merge, labelled "else" so the user sees what happens when
            // nothing fires. We pre-route this edge with two waypoints so
            // it goes around (rather than through) the branch containers:
            // right out of the decision to the rightmost branch's outer
            // edge, then straight down past the branches, then back in to
            // the merge. The waypoints are overwritten by user-edited ones
            // via applyEdgeWaypoints; this is just a sensible default.
            if (!hasElse) {
                var elseEdge = ctx.addStructuralEdge(decision, merge, "else");
                applyElseRoute(elseEdge, decision, merge, branchContainers);
            }
        }

        return merge;
    }

    /**
     * Adds two waypoints to the {@code else} edge so it routes orthogonally
     * around the branch containers instead of cutting straight through them.
     * Lays out as: right from the decision, down past the branches, left
     * back to the merge.
     *
     * <p>Waypoints live in the edge's owner-container local frame. For an
     * else edge between a decision diamond, branch containers, and a merge
     * — all siblings under the same parent — that means we can just use
     * each node's own {@code position} directly (it's already parent-local)
     * without any frame-translation gymnastics.
     */
    private void applyElseRoute(UiDiagramEdge edge,
                                UiDiagramNode decision,
                                UiDiagramNode merge,
                                java.util.List<UiDiagramNode> branchContainers) {
        if (decision.getPosition() == null || merge.getPosition() == null) return;
        final double GUTTER = 30;
        double decX = decision.getPosition().getX();
        double decY = decision.getPosition().getY();
        double mergeY = merge.getPosition().getY();
        double decW = decision.getWidth()  != null ? decision.getWidth()  : 80;
        double decH = decision.getHeight() != null ? decision.getHeight() : 80;
        double mergeH = merge.getHeight() != null ? merge.getHeight() : 80;
        // Rightmost edge of the rightmost branch, plus a small gutter so the
        // route line doesn't visually touch the box.
        double rightEdge = decX + decW;
        for (UiDiagramNode b : branchContainers) {
            if (b.getPosition() == null) continue;
            double bw = b.getWidth() != null ? b.getWidth() : 200;
            double bRight = b.getPosition().getX() + bw;
            if (bRight > rightEdge) rightEdge = bRight;
        }
        double routeX = rightEdge + GUTTER;
        double decisionMidY = decY + decH / 2.0;
        double mergeMidY = mergeY + mergeH / 2.0;
        edge.setWaypoints(java.util.List.of(
            Position.of(routeX, decisionMidY),
            Position.of(routeX, mergeMidY)
        ));
    }

    /**
     * Lays one branch of an if-step inside a synthetic container. The
     * container is sized to fit its children, the decision links to the
     * container with a labelled structural edge ({@code "x > 0"} / {@code "else"}),
     * and the container itself is returned so the caller can wire it to the
     * merge.
     *
     * <p>Empty branches still get a container (small, empty) so the label
     * remains visible — better than a stranded passthrough diamond.
     */
    private UiDiagramNode layoutBranchContainer(BlockData block, UiDiagramNode decision,
                                                String edgeLabel, double xOffset,
                                                String containerPath, BuildContext ctx) {
        UiDiagramNode container = ctx.runInContainerAt(
            WorkflowShapes::branchContainer,
            edgeLabel,
            null, null,
            xOffset,
            () -> {
                if (block == null || block.getSteps() == null) return;
                UiDiagramNode tail = null;
                for (StepData child : block.getSteps()) {
                    tail = appendStep(child, tail, ctx);
                }
            });

        // Tag the synthetic container with its insertion path so the editor
        // can post inserts into the right nested list (then-block of If "X",
        // else-block of If "X", etc.).
        container.put("containerPath", containerPath);

        // Reserve-prefix stepRef so the branch container itself is
        // draggable. Same lifecycle as the merge: visual scaffolding the
        // user can re-arrange, persisted via LayoutStore, not editable as
        // a step. Insert-after-the-branch is meaningless (the matching
        // gesture lives on the merge below the whole If), so we disable
        // that gesture too.
        container.setStepRef(SYNTHETIC_BRANCH_PREFIX + containerPath);
        container.setCanSelect(false);
        container.setCanDelete(false);
        container.setCanInsertAfter(false);

        ctx.addStructuralEdge(decision, container, edgeLabel);
        return container;
    }


    // -----------------------------------------------------------------------
    // Branching: ForEach
    // -----------------------------------------------------------------------

    /**
     * Renders a {@code ForEachData} step as a single container (carrying
     * the step's name as {@code stepRef}); the loop body lives as children
     * inside it. Both parallel and sequential iterations render the same
     * way — the {@code parallel} flag is purely runtime semantics, no
     * extra visual scaffolding around the container.
     */
    private UiDiagramNode appendForEach(ForEachData step, UiDiagramNode prev, BuildContext ctx) {
        String name = ctx.reserveStepName(step.getName());
        step.setName(name);
        UiDiagramNode loop = layoutLoopContainer(step, name, ctx);
        wireIncomingEdges(prev, loop, ctx);
        return loop;
    }

    private UiDiagramNode layoutLoopContainer(ForEachData step, String name, BuildContext ctx) {
        UiDiagramNode loop = ctx.runInContainer(
            WorkflowShapes::loopContainer,
            labelFor(step, name),
            name, step.getType(),
            () -> {
                if (step.getSteps() == null) return;
                UiDiagramNode tail = null;
                for (StepData child : step.getSteps()) {
                    tail = appendStep(child, tail, ctx);
                }
            });
        // Container path = the ForEach's own stepRef. The editor uses this
        // to insert into the right list — semantically equivalent to
        // inserting "after the foreach itself" but the operation lands
        // *inside* the body.
        loop.put("containerPath", "foreach:" + name);
        return loop;
    }

    // -----------------------------------------------------------------------
    // Containers: plain Block
    // -----------------------------------------------------------------------

    /**
     * A {@link BlockData} container is rendered as a container holding its
     * nested steps. Like ForEach, the block itself carries the {@code stepRef}
     * (it's a real step, not a synthetic wrapper).
     */
    private UiDiagramNode appendBlock(BlockData step, UiDiagramNode prev, BuildContext ctx) {
        String name = ctx.reserveStepName(step.getName());
        step.setName(name);

        UiDiagramNode block = ctx.runInContainer(
            WorkflowShapes::blockContainer,
            labelFor(step, name),
            name, step.getType(),
            () -> {
                if (step.getSteps() == null) return;
                UiDiagramNode tail = null;
                for (StepData child : step.getSteps()) {
                    tail = appendStep(child, tail, ctx);
                }
            });
        block.put("containerPath", "block:" + name);
        wireIncomingEdges(prev, block, ctx);
        return block;
    }

    // -----------------------------------------------------------------------
    // Shape + label mapping
    // -----------------------------------------------------------------------

    /**
     * Picks the {@link WorkflowShapes} decorator appropriate for the supplied
     * step. Returning a {@code Consumer<UiDiagramNode>} (rather than a string)
     * keeps the decoration atomic — shape + class + marker + border in one
     * step — which matters because {@code BuildContext} doesn't know about
     * the workflow domain at all.
     */
    private Consumer<UiDiagramNode> shapeFor(StepData step) {
        if (step instanceof CallWorkflowData)    return WorkflowShapes::call;
        if (step instanceof IfData)              return WorkflowShapes::decision;
        if (step instanceof ForEachData)         return WorkflowShapes::subprocess;
        if (step instanceof BlockData)           return WorkflowShapes::subprocess;
        if (step instanceof HaltData)            return WorkflowShapes::eventEnd;
        if (step instanceof JumpToData)          return WorkflowShapes::action;
        if (step instanceof HttpCallData)        return WorkflowShapes::action;
        if (step instanceof CodeData)            return WorkflowShapes::action;
        if (step instanceof AssignVariablesData) return WorkflowShapes::action;
        return WorkflowShapes::action;
    }

    /**
     * Header line + optional detail line. The first line is always
     * {@code "<name> : <type>"} so the user reads the step's type at a
     * glance without opening the side panel. Subsequent lines (rendered
     * smaller and muted by the shape) carry type-specific detail —
     * HTTP method + URL, called workflow, code language — but only when
     * we actually have something useful to show.
     */
    private String labelFor(StepData step, String name) {
        String header = name + " : " + step.getType();
        if (step instanceof HttpCallData http && http.getUrl() != null && !http.getUrl().isBlank()) {
            return header + "\n" + http.getMethod() + " " + truncate(http.getUrl(), 40);
        }
        if (step instanceof CallWorkflowData call && call.getWorkflow() != null) {
            return header + "\n→ " + call.getWorkflow();
        }
        if (step instanceof CodeData code && code.getLanguage() != null) {
            return header + "\n[" + code.getLanguage() + "]";
        }
        return header;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // -----------------------------------------------------------------------
    // Misc
    // -----------------------------------------------------------------------

    private String diagramIdFor(WorkflowData wf) {
        if (wf.getUid() != null && !wf.getUid().isBlank()) {
            return "wf-" + wf.getUid();
        }
        if (wf.getName() != null && !wf.getName().isBlank()) {
            return "wf-" + wf.getName();
        }
        return "wf-diagram";
    }

    /**
     * Recursively collects all step names in the workflow into {@code out}.
     * Walks into branches, loop bodies, and block contents — anywhere a
     * nested {@code StepData} can live. Used by {@link #build} to pre-seed
     * the auto-name counter so re-builds don't re-issue colliding names.
     */
    private void collectStepNames(List<StepData> steps, List<String> out) {
        if (steps == null) return;
        for (StepData s : steps) {
            if (s.getName() != null) out.add(s.getName());
            if (s instanceof IfData ifData) {
                if (ifData.getConditions() != null) {
                    for (IfData.Condition c : ifData.getConditions()) {
                        if (c.getThenBlock() != null) {
                            collectStepNames(c.getThenBlock().getSteps(), out);
                        }
                    }
                }
                if (ifData.getElseBlock() != null) {
                    collectStepNames(ifData.getElseBlock().getSteps(), out);
                }
            } else if (s instanceof ForEachData fe) {
                collectStepNames(fe.getSteps(), out);
            } else if (s instanceof BlockData block) {
                collectStepNames(block.getSteps(), out);
            }
        }
    }
}
