package ai.mindconnect.workflow.ui.diagram.app;

import ai.mindconnect.ui.ext.diagram.Position;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory map of {@code (workflowName, stepRef) → manual position},
 * separate from {@link WorkflowStore} because layout is a view concern and
 * doesn't belong in the workflow domain model.
 *
 * <p>Not persisted across restarts in Phase 3a — the goal is to validate
 * the editor loop, not to ship a production layout-saving system. If layout
 * persistence becomes a requirement, the natural extension is to serialise
 * this map alongside the saved workflow JSON.
 *
 * <p>Thread-safety: the editor mutates from request threads concurrently
 * (typical Spring controller), so the outer map is concurrent and inner
 * maps use {@link ConcurrentHashMap} via {@code computeIfAbsent}.
 */
@Component
public class LayoutStore {

    private final Map<String, Map<String, Position>> positions = new ConcurrentHashMap<>();

    /**
     * Per-workflow edge-waypoint overrides. Outer key = workflow name,
     * inner key = edge identity (the source and target stepRefs joined by
     * "->"), value = the polyline waypoints in absolute diagram coords.
     *
     * <p>Edge identity uses stepRefs rather than the diagram's per-build
     * edge ids (which are re-numbered on every rebuild). When the
     * underlying workflow structure changes — e.g. a step inserted between
     * source and target — the old edge no longer exists, so the override
     * is effectively orphaned. We rely on the editor to repaint that case
     * cleanly: a stale entry sits in the map but is never read because no
     * matching edge ever appears in subsequent builds.
     */
    private final Map<String, Map<String, List<Position>>> edgeWaypoints = new ConcurrentHashMap<>();

    /**
     * Encodes a source/target stepRef pair into a stable edge-identity
     * string. The "->" separator is fine because stepRefs are unique
     * within a workflow and don't contain it.
     */
    public static String edgeKey(String source, String target) {
        return source + "->" + target;
    }

    /**
     * Returns the position map for {@code workflowName}. Never returns
     * {@code null} — callers always get a (possibly empty) snapshot they
     * can iterate safely. Returned map is a defensive copy so callers
     * iterating it can't be surprised by concurrent updates.
     */
    public Map<String, Position> forWorkflow(String workflowName) {
        Map<String, Position> map = positions.get(workflowName);
        return map == null ? Map.of() : new HashMap<>(map);
    }

    public void setPosition(String workflowName, String stepRef, Position pos) {
        positions.computeIfAbsent(workflowName, k -> new ConcurrentHashMap<>())
                 .put(stepRef, pos);
    }

    /**
     * Drops a step's manual position, e.g. after the step was deleted from
     * the workflow. Silently no-ops if no override exists.
     */
    public void clearPosition(String workflowName, String stepRef) {
        Map<String, Position> map = positions.get(workflowName);
        if (map != null) {
            map.remove(stepRef);
        }
    }

    /**
     * Re-keys a position from {@code oldRef} to {@code newRef}, used when
     * a step is renamed. If no override exists for {@code oldRef} the call
     * is a no-op — that's fine, the renamed step will simply fall back to
     * the default stack-layout coordinate.
     */
    public void renameKey(String workflowName, String oldRef, String newRef) {
        Map<String, Position> map = positions.get(workflowName);
        if (map == null) return;
        Position p = map.remove(oldRef);
        if (p != null) {
            map.put(newRef, p);
        }
    }

    // ── Edge waypoints ─────────────────────────────────────────────────────

    /**
     * Returns the waypoint map for {@code workflowName}. Never returns null;
     * returned map is a defensive copy.
     */
    public Map<String, List<Position>> edgeWaypointsFor(String workflowName) {
        var map = edgeWaypoints.get(workflowName);
        return map == null ? Map.of() : new HashMap<>(map);
    }

    /**
     * Persists a list of waypoints for the edge identified by source/target
     * stepRefs. Passing an empty list clears the override (and the edge
     * reverts to a straight line).
     */
    public void setEdgeWaypoints(String workflowName, String source, String target,
                                 List<Position> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            var map = edgeWaypoints.get(workflowName);
            if (map != null) map.remove(edgeKey(source, target));
            return;
        }
        edgeWaypoints.computeIfAbsent(workflowName, k -> new ConcurrentHashMap<>())
                     .put(edgeKey(source, target), List.copyOf(waypoints));
    }
}
