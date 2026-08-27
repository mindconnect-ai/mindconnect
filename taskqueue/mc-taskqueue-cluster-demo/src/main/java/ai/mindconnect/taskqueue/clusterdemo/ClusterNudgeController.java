package ai.mindconnect.taskqueue.clusterdemo;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The nudge endpoints. All of them are accelerators over a truth that lives
 * in the store — every handler answers 204 before anything is proven, and a
 * caller that never hears back has lost nothing but latency.
 */
@RestController
@RequestMapping("/cluster")
public class ClusterNudgeController {

    private final ClusterProperties cluster;
    private final LocalTaskQueue queue;
    private final WorkerProcessManager workers;
    private final ChannelRelay relay;

    public ClusterNudgeController(ClusterProperties cluster, LocalTaskQueue queue,
                                  WorkerProcessManager workers, ChannelRelay relay) {
        this.cluster = cluster;
        this.queue = queue;
        this.workers = workers;
        this.relay = relay;
    }

    /**
     * "The queue changed — look now." On a worker: wake the dispatcher. On
     * the master: fan the nudge out to every worker (a child submitted on
     * node A may be claimable by node B, and only the master knows them all).
     */
    @PostMapping("/nudge")
    public ResponseEntity<Void> nudge() {
        queue.nudge();
        if (cluster.isMaster()) {
            workers.nudgeWorkers();
        }
        return ResponseEntity.noContent().build();
    }

    /** "The durable channel grew — read it." Master only; workers ignore it. */
    @PostMapping("/channels/nudge")
    public ResponseEntity<Void> channelsNudge() {
        if (cluster.isMaster()) {
            Thread.ofVirtual().start(relay::catchUp);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok(cluster.nodeId());
    }
}
