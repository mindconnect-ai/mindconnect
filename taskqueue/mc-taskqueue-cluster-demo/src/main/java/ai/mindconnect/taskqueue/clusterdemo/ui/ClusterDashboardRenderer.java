package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.clusterdemo.ClusterProperties;
import ai.mindconnect.taskqueue.clusterdemo.WorkerProcessManager;
import ai.mindconnect.taskqueue.clusterdemo.ui.TasksRenderer;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.jdbc.JdbcChannelStore;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import ai.mindconnect.taskqueue.jdbc.JdbcTaskStore;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiScrollPane;
import ai.mindconnect.ui.model.UiSection;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiSectionEntry;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The cluster at a glance, live: the queue of waiting tasks, the parked ones,
 * and ONE GROUP PER WORKER NODE showing what runs there right now — the
 * {@code lease_owner} column made visible. Every panel is a replace target,
 * patched on every task event and on every worker lifecycle change.
 */
@Component
public class ClusterDashboardRenderer {

    public static final String DASHBOARD_ID = "cluster-dashboard";

    private static final int MAX_ROWS = 50;

    /** The picked queue tab — server-side, like the task selection: the
     * section is a live patch target (its tab labels carry counts), so the
     * server must know which tab to mark active on every re-render. */
    private volatile String activeQueueTab = "queues-waiting";

    private final JdbcTaskStore store;
    private final JdbcChannelStore<TaskEvent> channelStore;
    private final ChannelRegistry registry;
    private final WorkerProcessManager workers;
    private final ClusterProperties cluster;

    public ClusterDashboardRenderer(JdbcTaskStore store, JdbcChannelStore<TaskEvent> channelStore,
                                    ChannelRegistry registry, WorkerProcessManager workers,
                                    ClusterProperties cluster) {
        this.store = store;
        this.channelStore = channelStore;
        this.registry = registry;
        this.workers = workers;
        this.cluster = cluster;
    }

    public void selectQueueTab(String sectionId) {
        this.activeQueueTab = sectionId;
    }

    public UiPage page() {
        var dashboard = UiStack.of(DASHBOARD_ID)
                .child(channelTile())
                .child(queuesSection())
                .child(nodeGroups());
        return UiPage.of("/tasks/dashboard", dashboard);
    }

    /** Everything that can change, replaced wholesale — idempotent on replay. */
    public UiPatch patch() {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("channel-tile", channelTile()))
                .patch(UiPatch.Operation.replace("queues", queuesSection()))
                .patch(UiPatch.Operation.replace("node-groups", nodeGroups()));
    }

    /**
     * The queues as tabs, counts in the labels. The section IS a patch target
     * (how else would the numbers move) — the picked tab survives because the
     * tab click reports back and {@code initialSection} re-marks it.
     */
    UiNode queuesSection() {
        Map<TaskStatus, Integer> counts = store.countByStatus();
        // QUEUED only: RUNNING tasks are shown (and counted) per node group.
        int waiting = counts.getOrDefault(TaskStatus.QUEUED, 0);
        int suspended = counts.getOrDefault(TaskStatus.SUSPENDED, 0);
        int completed = counts.getOrDefault(TaskStatus.COMPLETED, 0)
                + counts.getOrDefault(TaskStatus.FAILED, 0)
                + counts.getOrDefault(TaskStatus.CANCELLED, 0);
        var queues = UiSection.of("queues", "Queues").initialSection(activeQueueTab);
        queues.getSections().add(tab("queues-waiting", "Waiting (" + waiting + ")",
                UiScrollPane.of("queue-pane", queuePanel()).maxHeight("320px")));
        queues.getSections().add(tab("queues-suspended", "Suspended (" + suspended + ")",
                UiScrollPane.of("suspended-pane", suspendedPanel()).maxHeight("320px")));
        queues.getSections().add(tab("queues-completed", "Completed (" + completed + ")",
                UiScrollPane.of("completed-pane", completedPanel()).maxHeight("320px")));
        return queues;
    }

    private UiSectionEntry tab(String id, String label, UiNode content) {
        return UiSectionEntry.of(id, label, content)
                .onClick(UiTrigger.api("POST", "/tasks/dashboard/queues/tab/" + id));
    }

    /** The observation plane in one line: the durable channel's seq, live. */
    UiNode channelTile() {
        long seq = channelStore.lastSeq(TaskChannelBridge.ALL_TASKS);
        int subscribers = registry.<TaskEvent>find(TaskChannelBridge.ALL_TASKS)
                .map(channel -> channel.subscriberCount()).orElse(0);
        return UiText.of("channel-tile",
                        "channel \u00bbtasks\u00ab \u00b7 seq " + seq + " \u00b7 " + subscribers + " subscribers")
                .withCssClass("channel-tile");
    }

    UiNode queuePanel() {
        var table = UiTable.of("queue-panel", null)
                .column(UiColumn.text("title", "Task"))
                .column(UiColumn.text("shortId", "Id"))
                .column(UiColumn.number("attempt", "Attempt"))
                .column(UiColumn.number("priority", "Priority"))
                .rowAction(cancelAction());
        queued().forEach(task -> table.row(row(task)));
        return table;
    }

    UiNode suspendedPanel() {
        var table = UiTable.of("suspended-panel", null)
                .column(UiColumn.text("title", "Task"))
                .column(UiColumn.text("shortId", "Id"))
                .column(UiColumn.text("progress", "Progress"))
                .rowAction(cancelAction());
        store.byStatus(TaskStatus.SUSPENDED, MAX_ROWS).forEach(task -> table.row(row(task)));
        return table;
    }

    UiNode completedPanel() {
        var table = UiTable.of("completed-panel", null)
                .action(UiAction.danger("clear-completed", "Clear all completed tasks").icon("delete")
                        .confirm("Forget all finished task trees? Running and waiting tasks stay.")
                        .dispatch("POST", "/tasks/dashboard/purge"))
                .column(UiColumn.text("title", "Task"))
                .column(UiColumn.text("shortId", "Id"))
                .column(UiColumn.text("node", "Node"))
                .column(UiColumn.number("attempt", "Attempt"))
                .column(UiColumn.text("progress", "Result"));
        var done = new ArrayList<TaskRecord>();
        done.addAll(store.byStatus(TaskStatus.COMPLETED, MAX_ROWS));
        done.addAll(store.byStatus(TaskStatus.FAILED, MAX_ROWS));
        done.addAll(store.byStatus(TaskStatus.CANCELLED, MAX_ROWS));
        done.sort(Comparator.comparing(TaskRecord::endedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        done.stream().limit(MAX_ROWS).forEach(task -> table.row(row(task)));
        return table;
    }

    /** One table per managed worker: its process state, and what it runs NOW. */
    UiNode nodeGroups() {
        var groups = UiStack.of("node-groups");
        // The record itself says who runs it — nodeId is stamped at claim time.
        List<TaskRecord> running = store.byStatus(TaskStatus.RUNNING, MAX_ROWS);

        for (WorkerProcessManager.ManagedWorker worker : workers.list()) {
            String nodeId = "worker:" + worker.port();
            long runningHere = running.stream()
                    .filter(task -> nodeId.equals(task.nodeId())).count();
            var table = UiTable.of("node-" + worker.port(),
                            nodeId + " — " + worker.status()
                                    + (worker.pid() > 0 ? " · pid " + worker.pid() : "")
                                    + " · " + runningHere + " running"
                                    + (worker.restarts() > 0 ? " · restarts " + worker.restarts() : ""))
                    .column(UiColumn.text("title", "Running task"))
                    .column(UiColumn.text("shortId", "Id"))
                    .column(UiColumn.number("attempt", "Attempt"))
                    .column(UiColumn.text("progress", "Progress"))
                    .rowAction(cancelAction());
            if ("UP".equals(worker.status())) {
                table.action(UiAction.danger("kill-" + worker.port(), "Kill").icon("delete")
                        .confirm("SIGKILL " + nodeId + "? Its running tasks move to another "
                                + "worker once the lease expires — that is the demo.")
                        .dispatch("POST", "/tasks/dashboard/workers/" + worker.port() + "/kill"));
                table.action(UiAction.secondary("stop-" + worker.port(), "Stop")
                        .confirm("Stop " + nodeId + " for good? It will not be restarted.")
                        .dispatch("POST", "/tasks/dashboard/workers/" + worker.port() + "/stop"));
            } else {
                table.action(UiAction.primary("restart-" + worker.port(), "Start").icon("play")
                        .dispatch("POST", "/tasks/dashboard/workers/" + worker.port() + "/restart"));
                table.action(UiAction.secondary("delete-" + worker.port(), "Delete")
                        .confirm("Forget the worker slot :" + worker.port() + "?")
                        .dispatch("POST", "/tasks/dashboard/workers/" + worker.port() + "/delete"));
            }
            running.stream()
                    .filter(task -> nodeId.equals(task.nodeId()))
                    .forEach(task -> table.row(row(task)));
            groups.child(table);
        }
        groups.child(UiTable.of("node-actions", "Cluster")
                .column(UiColumn.text("what", ""))
                .action(UiAction.primary("start-worker", "Start another worker").icon("add")
                        .dispatch("POST", "/tasks/dashboard/workers/start")));
        return groups;
    }

    private UiAction cancelAction() {
        return UiAction.danger("cancel", "Cancel")
                .confirm("Cancel this task and all of its children?")
                .dispatch("POST", "/tasks/dashboard/{id}/cancel");
    }

    private List<TaskRecord> queued() {
        var queued = new ArrayList<>(store.byStatus(TaskStatus.QUEUED, MAX_ROWS));
        queued.sort(Comparator.comparingInt(TaskRecord::priority).reversed()
                .thenComparing(TaskRecord::submittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return queued;
    }

    private static Map<String, Object> row(TaskRecord task) {
        return Map.of(
                "id", task.id(),
                "title", TasksRenderer.taskTitle(task),
                "shortId", TasksRenderer.shortId(task.id()),
                "node", task.nodeId() == null ? "—" : task.nodeId(),
                "attempt", task.attempt(),
                "priority", task.priority(),
                "progress", TasksRenderer.progress(task));
    }
}
