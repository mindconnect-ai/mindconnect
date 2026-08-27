package ai.mindconnect.taskqueue.clusterdemo;

import java.time.Duration;

/** The few facts every cluster component needs, resolved once. */
public record ClusterProperties(String role,
                                int port,
                                String masterUrl,
                                int workers,
                                int workerBasePort,
                                int workerConcurrency,
                                Duration lease,
                                Duration taskRetention,
                                Duration channelMaxAge,
                                long channelKeepEvents) {

    public boolean isMaster() {
        return "master".equals(role);
    }

    public boolean isWorker() {
        return "worker".equals(role);
    }

    /** Readable in the {@code lease_owner} column: role + port. */
    public String nodeId() {
        return role + ":" + port;
    }
}
