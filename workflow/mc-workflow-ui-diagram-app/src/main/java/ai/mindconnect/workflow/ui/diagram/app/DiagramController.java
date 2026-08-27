package ai.mindconnect.workflow.ui.diagram.app;

import ai.mindconnect.workflow.edit.WorkflowMutator;
import ai.mindconnect.ui.ext.diagram.Position;
import ai.mindconnect.ui.ext.diagram.UiDiagram;
import ai.mindconnect.ui.ext.diagram.UiDiagramNode;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.ui.diagram.WorkflowDiagramBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Editor REST surface. Mutations all return the freshly-rebuilt
 * {@link UiDiagram} so the client doesn't have to reason about partial
 * updates — it just replaces its current canvas.
 *
 * <p>Two exceptions:
 * <ul>
 *   <li>{@code move-node} returns {@code 204 No Content} because the
 *       structure didn't change, only one position. The client already
 *       knows where it dropped the node; no point shipping a full diagram
 *       back across the wire.</li>
 *   <li>{@code save} returns the saved file path as text for the user to
 *       see in the UI; no diagram change either.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workflows")
public class DiagramController {

    private final WorkflowStore workflows;
    private final LayoutStore layouts;
    private final WorkflowDiagramBuilder builder = new WorkflowDiagramBuilder();
    private final WorkflowMutator mutator = new WorkflowMutator();

    /**
     * Convenience: builds the diagram with both layout maps from
     * {@link #layouts}. Every endpoint that returns a diagram funnels through
     * here so node positions and edge waypoints stay in sync without callers
     * needing to remember both.
     */
    private UiDiagram buildFor(String name, WorkflowData wf) {
        return builder.build(wf, layouts.forWorkflow(name), layouts.edgeWaypointsFor(name));
    }
    // Workflow-aware ObjectMapper — same one WorkflowStore uses for save —
    // so polymorphic StepData round-trips cleanly through GET/PUT.
    private final com.fasterxml.jackson.databind.ObjectMapper workflowMapper =
        ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory.create();

    // -----------------------------------------------------------------------
    // Listing + reading
    // -----------------------------------------------------------------------

    @GetMapping
    public String[] list() {
        return workflows.names().toArray(new String[0]);
    }

    @GetMapping("/step-types")
    public List<StepFactory.TypeOption> stepTypes() {
        return StepFactory.AVAILABLE;
    }

    @GetMapping("/{name}/diagram")
    public ResponseEntity<UiDiagram> diagram(@PathVariable String name) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(buildFor(name, wf));
    }

    // -----------------------------------------------------------------------
    // Mutations — structure
    // -----------------------------------------------------------------------

    /**
     * Insert a new step. Two modes:
     * <ul>
     *   <li>{@code afterStepRef} set → insert immediately after that step
     *       in the same containing list</li>
     *   <li>{@code afterStepRef} blank/null → insert at the very beginning
     *       (used when the user clicks the plus on the start-to-first edge)</li>
     * </ul>
     */
    @PostMapping("/{name}/insert-step")
    public ResponseEntity<UiDiagram> insertStep(@PathVariable String name,
                                                @RequestBody InsertStepRequest req) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();

        StepData newStep;
        try {
            newStep = StepFactory.create(req.stepType());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // Three insertion modes the client can pick:
        //   1. intoContainer + position   → into a nested list (then/else,
        //      foreach body, block body), at first or last position.
        //   2. afterStepRef               → after the given step in its own list.
        //   3. neither (or __start__)     → before the first top-level step.
        try {
            if (req.intoContainer() != null && !req.intoContainer().isBlank()) {
                var pos = "first".equalsIgnoreCase(req.position())
                    ? WorkflowMutator.Position.FIRST
                    : WorkflowMutator.Position.LAST;
                mutator.insertIntoContainer(wf, req.intoContainer(), pos, newStep);
            } else {
                String after = req.afterStepRef();
                boolean insertAtBeginning = after == null || after.isBlank()
                    || ai.mindconnect.workflow.ui.diagram.WorkflowDiagramBuilder.SYNTHETIC_START_REF.equals(after);
                if (insertAtBeginning) {
                    mutator.insertAtStart(wf, newStep);
                } else {
                    mutator.insertAfter(wf, after, newStep);
                }
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(buildFor(name, wf));
    }

    @DeleteMapping("/{name}/steps/{stepRef}")
    public ResponseEntity<UiDiagram> deleteStep(@PathVariable String name,
                                                @PathVariable String stepRef) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();

        if (!mutator.delete(wf, stepRef)) {
            return ResponseEntity.notFound().build();
        }
        // Drop the layout override too — keeping it would just waste memory.
        layouts.clearPosition(name, stepRef);
        return ResponseEntity.ok(buildFor(name, wf));
    }

    @PutMapping("/{name}/steps/{stepRef}/name")
    public ResponseEntity<UiDiagram> renameStep(@PathVariable String name,
                                                @PathVariable String stepRef,
                                                @RequestParam String newName) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();

        if (!mutator.rename(wf, stepRef, newName)) {
            return ResponseEntity.notFound().build();
        }
        // Re-key the layout override so the renamed step keeps its position.
        layouts.renameKey(name, stepRef, newName);
        return ResponseEntity.ok(buildFor(name, wf));
    }

    /**
     * Returns the {@link StepData} for the named step as a JSON tree, using
     * the workflow ObjectMapper so polymorphic subtypes serialise with the
     * right discriminator. Spring's default ObjectMapper doesn't know about
     * the workflow domain, so we hand it back a string ourselves.
     */
    @GetMapping(value = "/{name}/steps/{stepRef}", produces = "application/json")
    public ResponseEntity<String> getStep(@PathVariable String name,
                                          @PathVariable String stepRef) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();
        var loc = mutator.find(wf, stepRef);
        if (loc == null) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(workflowMapper.writeValueAsString(loc.step()));
        } catch (Exception e) {
            log.error("Failed to serialise step {}", stepRef, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Applies a partial update to the named step. The body is a flat
     * {@code Map<String, Object>} of field names → new values; only fields
     * present in the map are overwritten on the step. Unknown fields are
     * silently ignored so an over-eager client doesn't break the workflow.
     *
     * <p>Renaming via this endpoint is supported (set {@code name} in the
     * patch). The position override is re-keyed in {@link LayoutStore} just
     * like {@link #renameStep} does.
     */
    @PutMapping("/{name}/steps/{stepRef}")
    public ResponseEntity<UiDiagram> updateStep(@PathVariable String name,
                                                @PathVariable String stepRef,
                                                @RequestBody Map<String, Object> patch) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();
        var loc = mutator.find(wf, stepRef);
        if (loc == null) return ResponseEntity.notFound().build();

        // Merge patch into the existing step via Jackson: serialise the step,
        // overlay the patch keys, deserialise back into the concrete type.
        // Keeps polymorphism (CodeData / HttpCallData / …) intact without us
        // having to dispatch per type. Errors fall through as 400.
        StepData merged;
        try {
            var node = workflowMapper.valueToTree(loc.step());
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
                return ResponseEntity.badRequest().build();
            }
            for (var entry : patch.entrySet()) {
                obj.set(entry.getKey(), workflowMapper.valueToTree(entry.getValue()));
            }
            merged = workflowMapper.treeToValue(obj, loc.step().getClass());
        } catch (Exception e) {
            log.warn("Failed to merge patch for step {}: {}", stepRef, e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // Swap the old step for the merged one in its containing list — keeps
        // tree positions stable so subsequent edits keep working.
        loc.container().set(loc.index(), merged);

        // If the patch renamed the step, follow the same layout-rekey dance
        // the explicit rename endpoint does.
        String newName = merged.getName();
        if (newName != null && !newName.equals(stepRef)) {
            layouts.renameKey(name, stepRef, newName);
        }
        return ResponseEntity.ok(buildFor(name, wf));
    }

    // -----------------------------------------------------------------------
    // Mutations — layout
    // -----------------------------------------------------------------------

    /**
     * Persists a drag-committed position. The client sends the new
     * <em>absolute</em> top-left position of the dragged node; the server
     * stores it under the step's name. Since the diagram model nests
     * children inside containers (with relative positions), the override
     * is interpreted as <em>relative to the dragged node's parent</em> —
     * for top-level nodes the relative and absolute coordinates coincide,
     * for nested nodes the controller must subtract the parent's absolute
     * position before storing.
     *
     * <p>This is the key advantage of the nested-children model: moving a
     * container needs no descendant book-keeping at all, because the
     * children's relative positions inside the container don't change.
     */
    @PostMapping("/{name}/move-node")
    public ResponseEntity<Void> moveNode(@PathVariable String name,
                                         @RequestBody MoveNodeRequest req) {
        WorkflowData wf = workflows.get(name);
        if (wf == null) return ResponseEntity.notFound().build();

        // Find the dragged node in the current diagram so we know its
        // parent's absolute position. Without it we can't translate the
        // client's absolute coords back to the relative form we need to
        // store. A fresh build with existing overrides applied gives us
        // the same picture the client is looking at.
        UiDiagram diagram = buildFor(name, wf);
        double[] parentAbs = findParentAbsolute(diagram.getNodes(), req.stepRef(), 0, 0);
        if (parentAbs == null) parentAbs = new double[] { 0, 0 };
        double relX = req.x() - parentAbs[0];
        double relY = req.y() - parentAbs[1];

        layouts.setPosition(name, req.stepRef(), Position.of(relX, relY));
        return ResponseEntity.noContent().build();
    }

    /**
     * Walks the diagram tree to find the absolute top-left of the parent of
     * {@code stepRef}. Returns {@code [0, 0]} if the node is top-level (no
     * parent) or not found — both cases mean "relative = absolute" for the
     * override, which is the correct degenerate behaviour.
     */
    private double[] findParentAbsolute(java.util.List<UiDiagramNode> siblings,
                                        String stepRef, double parentAbsX, double parentAbsY) {
        if (siblings == null) return new double[] { parentAbsX, parentAbsY };
        for (UiDiagramNode n : siblings) {
            if (stepRef.equals(n.getStepRef())) {
                return new double[] { parentAbsX, parentAbsY };
            }
            if (n.getChildren() != null) {
                double ax = parentAbsX + (n.getPosition() != null ? n.getPosition().getX() : 0);
                double ay = parentAbsY + (n.getPosition() != null ? n.getPosition().getY() : 0);
                double[] hit = findParentAbsolute(n.getChildren(), stepRef, ax, ay);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * Persists the waypoint polyline for the edge identified by source and
     * target stepRefs. Replies 204 with no body — the client already has
     * the new shape locally; the server-side record just ensures it
     * survives the next full rebuild. An empty / null waypoint list clears
     * any previously stored override (edge reverts to a straight line).
     */
    @PostMapping("/{name}/edge-waypoints")
    public ResponseEntity<Void> setEdgeWaypoints(@PathVariable String name,
                                                 @RequestBody EdgeWaypointsRequest req) {
        if (workflows.get(name) == null) return ResponseEntity.notFound().build();
        var positions = req.waypoints() == null ? java.util.List.<Position>of()
            : req.waypoints().stream().map(w -> Position.of(w.x(), w.y())).toList();
        layouts.setEdgeWaypoints(name, req.source(), req.target(), positions);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    @PostMapping("/{name}/save")
    public ResponseEntity<Map<String, String>> save(@PathVariable String name) {
        try {
            var path = workflows.save(name);
            return ResponseEntity.ok(Map.of("savedTo", path.toAbsolutePath().toString()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            log.error("Failed to save workflow {}", name, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // -----------------------------------------------------------------------
    // Request bodies
    // -----------------------------------------------------------------------

    /**
     * Insert-step request. Use one of:
     * <ul>
     *   <li>{@code afterStepRef} — insert after the named step</li>
     *   <li>{@code intoContainer} + optional {@code position} ("first"/"last",
     *       default "last") — insert into a container's children list</li>
     *   <li>both null — insert at the very beginning of the workflow</li>
     * </ul>
     */
    public record InsertStepRequest(String afterStepRef, String stepType,
                                    String intoContainer, String position) {}
    public record MoveNodeRequest(String stepRef, double x, double y) {}

    public record WaypointDto(double x, double y) {}
    public record EdgeWaypointsRequest(String source, String target,
                                       List<WaypointDto> waypoints) {}
}
