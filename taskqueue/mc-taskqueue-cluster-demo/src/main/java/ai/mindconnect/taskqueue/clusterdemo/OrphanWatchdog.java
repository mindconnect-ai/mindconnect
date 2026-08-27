package ai.mindconnect.taskqueue.clusterdemo;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A worker without its master is an orphan: nobody watches it, nobody can
 * stop it from the dashboard, and its port blocks the slot when the next
 * master starts — the recipe for "the worker that should be dead keeps
 * claiming tasks with last week's jar". A master killed with SIGKILL never
 * runs its shutdown hook, so the WORKER has to notice on its own: three
 * missed health checks in a row and it exits.
 */
@Component
public class OrphanWatchdog {

    private static final Logger log = LoggerFactory.getLogger(OrphanWatchdog.class);

    private final ClusterProperties cluster;
    private final ClusterHttp http;

    public OrphanWatchdog(ClusterProperties cluster, ClusterHttp http) {
        this.cluster = cluster;
        this.http = http;
    }

    @PostConstruct
    void watch() {
        if (!cluster.isWorker()) return;
        Thread.ofVirtual().name("orphan-watchdog").start(() -> {
            int missed = 0;
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                missed = http.isUp(cluster.masterUrl()) ? 0 : missed + 1;
                if (missed >= 3) {
                    log.warn("Master {} gone for 3 checks — exiting so the slot stays clean",
                            cluster.masterUrl());
                    System.exit(0);
                }
            }
        });
    }
}
