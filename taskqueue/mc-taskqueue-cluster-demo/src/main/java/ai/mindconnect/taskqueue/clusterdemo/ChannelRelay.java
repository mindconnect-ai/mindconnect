package ai.mindconnect.taskqueue.clusterdemo;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.jdbc.JdbcChannelStore;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The master's half of the observation plane: mirrors the durable channel
 * into the master's LOCAL registry via {@code publishAt}, so the whole board
 * (SSE, replay, inspector) works unchanged — it cannot tell a clustered
 * channel from a local one.
 *
 * <p>Worker nudges trigger a catch-up immediately; a slow poll runs anyway,
 * because a nudge is an accelerator, not a delivery. {@code readAfter} keeps
 * one cursor, so double nudges and nudge/poll overlap are harmless.
 */
@Component
public class ChannelRelay {

    private static final Logger log = LoggerFactory.getLogger(ChannelRelay.class);
    private static final int BATCH = 500;

    private final ClusterProperties cluster;
    private final JdbcChannelStore<TaskEvent> store;
    private final ChannelRegistry registry;
    private final Object catchUpLock = new Object();
    private volatile long lastSeq;
    private volatile boolean open = true;

    public ChannelRelay(ClusterProperties cluster, JdbcChannelStore<TaskEvent> store,
                        ChannelRegistry registry) {
        this.cluster = cluster;
        this.store = store;
        this.registry = registry;
    }

    @PostConstruct
    void start() {
        if (!cluster.isMaster()) return;
        Thread.ofVirtual().name("channel-relay").start(() -> {
            while (open) {
                try {
                    catchUp();
                    Thread.sleep(3000);       // the fallback; nudges make it instant
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warn("Channel relay pass failed, retrying in 3s: {}", e.getMessage());
                    try {
                        Thread.sleep(3000);       // backoff — not a hot loop on a dead database
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    /** Reads everything new from the store and mirrors it, seq and all. */
    public void catchUp() {
        synchronized (catchUpLock) {
            Channel<TaskEvent> live = registry.channel(TaskChannelBridge.ALL_TASKS);
            while (true) {
                var events = store.readAfter(TaskChannelBridge.ALL_TASKS, lastSeq, BATCH);
                if (events.isEmpty()) return;
                for (Channel.Event<TaskEvent> event : events) {
                    live.publishAt(event.seq(), event.value());
                    lastSeq = event.seq();
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        open = false;
    }
}
