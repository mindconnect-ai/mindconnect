package ai.mindconnect.taskqueue.clusterdemo;

import ai.mindconnect.channel.PersistentChannel;
import ai.mindconnect.channel.PersistentChannels;
import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * The worker's half of the observation plane: every queue event goes to the
 * DURABLE channel first — the store assigns the seq, the local registry
 * mirrors it — and the master gets a nudge saying "there is more". The nudge
 * carries nothing; the master reads the store, so a lost nudge is a delay,
 * not a gap.
 *
 * <p>Also nudges the master's QUEUE fan-out on submit/wake: a child submitted
 * here may need to be claimed by a dispatcher on another node.
 */
@Component
public class WorkerEventPublisher implements TaskListener {

    private final ClusterProperties cluster;
    private final ClusterHttp http;
    private final LocalTaskQueue queue;
    private final PersistentChannel<TaskEvent> channel;

    public WorkerEventPublisher(ClusterProperties cluster, ClusterHttp http, LocalTaskQueue queue,
                                PersistentChannels<TaskEvent> channels) {
        this.cluster = cluster;
        this.http = http;
        this.queue = queue;
        this.channel = channels.channel(TaskChannelBridge.ALL_TASKS);
    }

    @PostConstruct
    void attach() {
        if (cluster.isWorker()) {
            queue.addListener(this);
        }
    }

    @Override public void onSubmitted(TaskRecord task) { publish(TaskEvent.Type.SUBMITTED, task, true); }

    @Override public void onStarted(TaskRecord task) { publish(TaskEvent.Type.STARTED, task, false); }

    @Override public void onStateChanged(TaskRecord task) { publish(TaskEvent.Type.STATE, task, false); }

    @Override public void onSuspended(TaskRecord task) { publish(TaskEvent.Type.SUSPENDED, task, false); }

    @Override public void onWoken(TaskRecord task) { publish(TaskEvent.Type.WOKEN, task, true); }

    @Override public void onTerminal(TaskRecord task) { publish(TaskEvent.Type.TERMINAL, task, true); }

    private void publish(TaskEvent.Type type, TaskRecord task, boolean queueChanged) {
        channel.publish(new TaskEvent(type, task));
        http.nudge(cluster.masterUrl(), "/cluster/channels/nudge");
        if (queueChanged) {
            // Another node may have to claim what this transition freed.
            http.nudge(cluster.masterUrl(), "/cluster/nudge");
        }
    }
}
