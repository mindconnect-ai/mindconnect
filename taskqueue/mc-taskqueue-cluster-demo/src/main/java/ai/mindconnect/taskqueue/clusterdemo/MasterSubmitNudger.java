package ai.mindconnect.taskqueue.clusterdemo;

import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * The master claims nothing, so its own submits and wakes would sit in the
 * store until a worker's next poll — this listener turns them into immediate
 * worker nudges. Accelerator only, as always.
 */
@Component
public class MasterSubmitNudger implements TaskListener {

    private final ClusterProperties cluster;
    private final LocalTaskQueue queue;
    private final WorkerProcessManager workers;

    public MasterSubmitNudger(ClusterProperties cluster, LocalTaskQueue queue,
                              WorkerProcessManager workers) {
        this.cluster = cluster;
        this.queue = queue;
        this.workers = workers;
    }

    @PostConstruct
    void attach() {
        if (cluster.isMaster()) {
            queue.addListener(this);
        }
    }

    @Override
    public void onSubmitted(TaskRecord task) {
        workers.nudgeWorkers();
    }

    @Override
    public void onWoken(TaskRecord task) {
        workers.nudgeWorkers();
    }
}
