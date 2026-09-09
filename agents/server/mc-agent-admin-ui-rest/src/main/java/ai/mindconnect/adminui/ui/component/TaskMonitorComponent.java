package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.service.TaskMonitor;
import ai.mindconnect.adminui.service.TaskMonitor.Counts;
import ai.mindconnect.adminui.service.TaskMonitor.Snapshot;
import ai.mindconnect.adminui.service.TaskMonitor.TaskView;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTree;
import ai.mindconnect.ui.model.UiTreeNode;
import ai.mindconnect.ui.model.UiTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The task manager's two faces: the badge in the header (how many tasks run
 * right now, a click opens the dialog) and the dialog body (the live task
 * tree with per-task Cancel, plus what finished recently).
 *
 * <p>Both are rendered from one {@link Snapshot} and both are addressed by
 * fixed ids, so the same {@link #livePatch} that the SSE stream sends can
 * update the badge on every page and the dialog whenever it happens to be
 * open — a REPLACE on the closed dialog's id finds no target and is dropped
 * by the client, which is exactly right.
 */
public final class TaskMonitorComponent {

    /** The badge's DOM id — the live patch's target, see {@link TaskMonitor#CHANNEL_ID}. */
    public static final String BADGE_ID = TaskMonitor.CHANNEL_ID;
    public static final String DIALOG_ID = "task-monitor-dialog";
    public static final String BODY_ID = "task-monitor-body";

    public static final String OPEN_URL = "/admin/api/tasks";
    public static final String CLOSE_URL = "/admin/api/tasks/close";

    private TaskMonitorComponent() { }

    // ── header badge ────────────────────────────────────────────────────────

    /**
     * The header badge: an activity icon and "3 running" (or "idle"). The
     * outer stack carries the id the live patch replaces, and a class that
     * says whether anything is going on so the stylesheet can light it up.
     *
     * <p>The count is deliberately a sibling of the button, not its label.
     * The button keeps focus after the click that opens the dialog, and the
     * morpher leaves the children of the focused element alone (Idiomorph's
     * {@code ignoreActiveValue}, meant for a field someone is typing in) —
     * a count inside the button would freeze at the value it had when the
     * dialog was opened. A span is never focused, so it always updates. The
     * whole pill is clickable through the stack's own trigger; the button is
     * what a keyboard reaches.
     */
    public static UiStack badge(Counts counts) {
        String label = !counts.busy() ? "idle"
                : counts.waiting() == 0 ? counts.running() + " running"
                : counts.running() + " running · " + counts.waiting() + " waiting";
        UiAction open = UiAction.link(BADGE_ID + "-link", "Task queue").icon("activity")
                .appearance(UiAction.Appearance.ICON)
                .dispatch("GET", OPEN_URL);
        UiStack badge = UiStack.of(BADGE_ID).direction(UiStack.Direction.HORIZONTAL).gap(4)
                .child(open)
                .child(text(BADGE_ID + "-count", label, "task-monitor-count"));
        badge.setOnClick(UiTrigger.api("GET", OPEN_URL));
        badge.setTitle("Task queue — click to open the task manager");
        badge.withCssClass("task-monitor-badge" + (counts.busy() ? " is-busy" : ""));
        return badge;
    }

    public static UiStack badge(Snapshot snapshot) {
        return badge(snapshot.counts());
    }

    // ── dialog ──────────────────────────────────────────────────────────────

    /** The dialog body for {@code userId}: live tree, recent list, Close. */
    public static UiStack body(Snapshot snapshot, String userId) {
        UiStack body = UiStack.of(BODY_ID);
        body.child(summary(snapshot));
        body.child(activeTree(snapshot, userId));
        body.child(recentList(snapshot));
        body.child(UiAction.secondary("task-monitor-close", "Close").icon("close")
                .dispatch("POST", CLOSE_URL));
        return body;
    }

    /** One {@code patch} frame of the user stream: badge on every page, dialog body when it is open. */
    public static UiPatch livePatch(Snapshot snapshot, String userId) {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(BADGE_ID, badge(snapshot)))
                .patch(UiPatch.Operation.replace(BODY_ID, body(snapshot, userId)));
    }

    private static UiText summary(Snapshot snapshot) {
        String text = snapshot.active().isEmpty()
                ? "The task queue is idle."
                : snapshot.runningCount() + " running, " + snapshot.queuedCount() + " queued, "
                  + snapshot.suspendedCount() + " suspended (waiting on sub-tasks).";
        UiText summary = UiText.of("task-monitor-summary", text);
        summary.withCssClass("sui-hint");
        return summary;
    }

    /**
     * Live tasks as a tree: a turn, under it the tool calls it dispatched,
     * under a {@code run_agent} call the sub-agent's turn — the shape the
     * queue's parent links already have. A task whose parent is not live
     * any more (it finished first) is shown as a root.
     */
    private static UiTree activeTree(Snapshot snapshot, String userId) {
        UiTree tree = UiTree.of("task-monitor-tree", "Running now");
        tree.withCssClass("task-monitor-tree");
        if (snapshot.active().isEmpty()) {
            tree.node(UiTreeNode.of("task-monitor-none", "No task is running.").icon("check"));
            return tree;
        }
        Map<String, UiTreeNode> nodes = new LinkedHashMap<>();
        for (TaskView view : snapshot.active()) {
            nodes.put(view.id(), node(view, userId, snapshot.at()));
        }
        for (TaskView view : snapshot.active()) {
            UiTreeNode node = nodes.get(view.id());
            String parent = view.task().parentTaskId();
            if (parent != null && nodes.containsKey(parent)) {
                nodes.get(parent).child(node);
            } else {
                tree.node(node);
            }
        }
        return tree;
    }

    private static UiTreeNode node(TaskView view, String userId, Instant now) {
        UiTreeNode node = UiTreeNode.of("task-" + view.id(), view.label())
                .icon(icon(view.task()))
                .open(true);
        node.labelNode(row(view, userId, now));
        node.withCssClass("task-monitor-node task-monitor-node--" + view.status().name().toLowerCase());
        return node;
    }

    /** One row: name, what it does, status pill, owner, elapsed time, and Cancel when allowed. */
    private static UiStack row(TaskView view, String userId, Instant now) {
        UiStack row = UiStack.of("task-row-" + view.id())
                .direction(UiStack.Direction.HORIZONTAL).gap(8);
        row.withCssClass("task-monitor-row");
        row.child(text("task-name-" + view.id(), view.label(), "task-monitor-name"));
        if (view.detail() != null) {
            row.child(text("task-detail-" + view.id(), view.detail(), "task-monitor-detail sui-hint"));
        }
        row.child(statusPill(view));
        row.child(text("task-owner-" + view.id(), view.owner() == null ? "—" : view.owner(), "task-monitor-owner sui-hint"));
        row.child(text("task-time-" + view.id(), elapsed(view.task(), now), "task-monitor-time sui-hint"));
        if (TaskMonitor.mayCancel(view, userId)) {
            row.child(UiAction.danger("task-cancel-" + view.id(), "Cancel").icon("ban")
                    .appearance(UiAction.Appearance.ICON)
                    .confirm("Cancel '" + view.label() + "'? Its sub-tasks are cancelled with it.")
                    .dispatch("POST", cancelUrl(view.id())));
        }
        return row;
    }

    private static UiText statusPill(TaskView view) {
        TaskRecord task = view.task();
        String label = task.cancelRequested() && !task.status().terminal()
                ? "cancelling"
                : task.status().name().toLowerCase();
        if (task.attempt() > 1 && !task.status().terminal()) label += " · attempt " + task.attempt();
        return text("task-status-" + view.id(), label,
                "task-monitor-status task-monitor-status--" + task.status().name().toLowerCase());
    }

    /** What finished last — a failure's reason is the thing one opens this for. */
    private static UiNode recentList(Snapshot snapshot) {
        UiTree list = UiTree.of("task-monitor-recent", "Finished recently");
        list.withCssClass("task-monitor-tree task-monitor-recent");
        if (snapshot.recent().isEmpty()) {
            list.node(UiTreeNode.of("task-monitor-recent-none", "Nothing has finished yet."));
            return list;
        }
        for (TaskView view : snapshot.recent()) {
            UiTreeNode node = UiTreeNode.of("task-" + view.id(), view.label()).icon(icon(view.task()));
            node.labelNode(row(view, null, snapshot.at()));
            node.withCssClass("task-monitor-node task-monitor-node--" + view.status().name().toLowerCase());
            if (view.task().failure() != null && view.task().failure().message() != null) {
                node.content(text("task-failure-" + view.id(), view.task().failure().message(), "task-monitor-failure"));
                node.open(false);
            }
            list.node(node);
        }
        return list;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    public static String cancelUrl(String taskId) {
        return OPEN_URL + "/" + java.net.URLEncoder.encode(taskId, java.nio.charset.StandardCharsets.UTF_8) + "/cancel";
    }

    private static UiText text(String id, String value, String cssClass) {
        UiText text = UiText.of(id, value);
        text.withCssClass(cssClass);
        return text;
    }

    private static String icon(TaskRecord task) {
        return switch (task.type()) {
            case "agent.turn" -> "bot";
            case "agent.tool" -> "wrench";
            default -> "activity";
        };
    }

    /** "12s", "3m 05s", "1h 02m" — since the task started, or since it was submitted while it waits. */
    static String elapsed(TaskRecord task, Instant now) {
        Instant from = task.startedAt() != null ? task.startedAt() : task.submittedAt();
        if (from == null) return "";
        Instant to = task.endedAt() != null ? task.endedAt() : now;
        Duration d = Duration.between(from, to);
        if (d.isNegative()) d = Duration.ZERO;
        long h = d.toHours(), m = d.toMinutesPart(), s = d.toSecondsPart();
        String text = h > 0 ? String.format("%dh %02dm", h, m)
                : m > 0 ? String.format("%dm %02ds", m, s)
                : s + "s";
        return task.status() == TaskStatus.QUEUED && task.startedAt() == null ? "waiting " + text : text;
    }

    /** Ids of every live task, root first — handy for tests and logs. */
    static List<String> order(Snapshot snapshot) {
        List<String> ids = new ArrayList<>();
        for (TaskView v : snapshot.active()) ids.add(v.id());
        return ids;
    }
}
