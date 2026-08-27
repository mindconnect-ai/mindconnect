package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.taskqueue.clusterdemo.ClusterProperties;
import ai.mindconnect.taskqueue.clusterdemo.WorkerProcessManager;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The dashboard page and its verbs. Actions answer with a fresh dashboard
 * patch so the operator sees the result at once; everyone else gets the same
 * patch pushed over the dashboard stream (task events and worker lifecycle
 * changes both trigger it).
 */
@RestController
@RequestMapping("/tasks/dashboard")
public class ClusterUiController {

    private final ClusterDashboardRenderer dashboard;
    private final WorkerProcessManager workers;
    private final TaskQueue queue;
    private final TaskStore store;
    private final ClusterProperties cluster;

    public ClusterUiController(ClusterDashboardRenderer dashboard, WorkerProcessManager workers,
                               TaskQueue queue, TaskStore store, ClusterProperties cluster) {
        this.dashboard = dashboard;
        this.workers = workers;
        this.queue = queue;
        this.store = store;
        this.cluster = cluster;
    }

    @GetMapping
    public UiPage dashboard() {
        return withStream(dashboard.page());
    }

    /** The dashboard page always advertises its stream — F5 re-attaches alone. */
    static UiPage withStream(UiPage page) {
        page.setActiveStreams(List.of(UiPage.ActiveStream.of(
                ClusterDashboardRenderer.DASHBOARD_ID,
                "/tasks/api/dashboard-stream", "Cluster events", "/tasks/dashboard")));
        return page;
    }

    /** The tab click reporting back — keeps the live re-render on the picked tab. */
    @PostMapping("/queues/tab/{sectionId}")
    public UiPatch queueTab(@PathVariable String sectionId) {
        dashboard.selectQueueTab(sectionId);
        return UiPatch.of();   // the switch already happened client-side
    }

    /** Forgets finished task TREES — running and waiting families stay whole. */
    @PostMapping("/purge")
    public UiPatch purge() {
        int removed = store.purgeTerminal(java.time.Instant.now());
        return dashboard.patch().toast(removed == 0
                ? UiToast.info("Nothing to clear — no finished task trees")
                : UiToast.success("Cleared " + removed + " finished task" + (removed == 1 ? "" : "s")));
    }

    @PostMapping("/{id}/cancel")
    public UiPatch cancel(@PathVariable String id) {
        boolean accepted = queue.cancel(id);
        return dashboard.patch().toast(accepted
                ? UiToast.info("Cancel requested for " + id + " — cascades to its children")
                : UiToast.warn("Task " + id + " is already terminal"));
    }

    @PostMapping("/workers/start")
    public UiPatch start() {
        int port = workers.startWorker();
        return dashboard.patch().toast(UiToast.success("Starting worker :" + port));
    }

    @PostMapping("/workers/{port}/stop")
    public UiPatch stop(@PathVariable int port) {
        return workers.stopWorker(port)
                ? dashboard.patch().toast(UiToast.info("Stopped worker :" + port))
                : dashboard.patch().toast(UiToast.warn("No worker :" + port));
    }

    @PostMapping("/workers/{port}/kill")
    public UiPatch kill(@PathVariable int port) {
        return workers.killWorker(port)
                ? dashboard.patch().toast(UiToast.warn("Killed worker :" + port
                        + " — its running tasks move on when the lease ("
                        + cluster.lease().toSeconds() + "s) expires"))
                : dashboard.patch().toast(UiToast.warn("No worker :" + port));
    }

    /** Only a stopped worker can be deleted — stopping and forgetting stay two steps. */
    @PostMapping("/workers/{port}/delete")
    public UiPatch delete(@PathVariable int port) {
        return workers.deleteWorker(port)
                ? dashboard.patch().toast(UiToast.info("Deleted worker slot :" + port))
                : dashboard.patch().toast(UiToast.warn("Worker :" + port
                        + " is running (or set to restart) — stop it first"));
    }

    @PostMapping("/workers/{port}/restart")
    public UiPatch restart(@PathVariable int port) {
        return workers.restartWorker(port)
                ? dashboard.patch().toast(UiToast.success("Restarting worker :" + port))
                : dashboard.patch().toast(UiToast.warn("Worker :" + port + " is running or unknown"));
    }
}
