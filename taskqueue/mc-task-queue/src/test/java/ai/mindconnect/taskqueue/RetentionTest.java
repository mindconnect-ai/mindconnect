package ai.mindconnect.taskqueue;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.memory.InMemoryChannelStore;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory does not grow forever: the maintenance loop forgets finished task
 * trees after the configured retention, and a channel store keeps only its
 * newest events — both opt-in, both the operator's one-time decision.
 */
class RetentionTest {

    @Test
    void maintenanceForgetsTerminalRecordsPastRetention() throws Exception {
        var store = new InMemoryTaskStore();
        try (var queue = new LocalTaskQueue(store)
                .withRetention(Duration.ZERO.plusMillis(1))   // everything terminal is "old"
                .withMaintenanceInterval(Duration.ofMillis(50))) {
            queue.register("noop", ctx -> TaskOutcome.done("ok"));
            String id = queue.submit(TaskSubmission.of("noop", Map.of()));
            queue.await(id, Duration.ofSeconds(5));

            long deadline = System.currentTimeMillis() + 5000;
            while (!store.byStatus(TaskStatus.COMPLETED, 10).isEmpty()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(store.byStatus(TaskStatus.COMPLETED, 10)).isEmpty();
        }
    }

    @Test
    void channelPurgeKeepsNewestEventsAndTheSeqSpace() {
        var store = new InMemoryChannelStore<String>();
        for (int i = 0; i < 10; i++) {
            store.append("c", "event-" + i);
        }
        long last = store.lastSeq("c");

        int purged = store.purgeBefore("c", last - 3 + 1);    // keep the newest 3

        assertThat(purged).isEqualTo(7);
        assertThat(store.lastSeq("c")).isEqualTo(last);       // head untouched
        List<Channel.Event<String>> kept = store.readAfter("c", 0, 100);
        assertThat(kept).extracting(Channel.Event::seq).containsExactly(8L, 9L, 10L);
    }

    @Test
    void channelPurgeByAgeForgetsOldEventsEvenWhenTrafficStops() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        var clock = new java.util.concurrent.atomic.AtomicReference<>(t0);
        var store = new InMemoryChannelStore<String>(new Clock() {
            @Override public Instant instant() { return clock.get(); }
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        });
        store.append("c", "old-1");
        store.append("c", "old-2");
        clock.set(t0.plus(Duration.ofHours(2)));
        store.append("c", "fresh");

        int purged = store.purgeOlderThan("c", clock.get().minus(Duration.ofHours(1)));

        assertThat(purged).isEqualTo(2);
        assertThat(store.lastSeq("c")).isEqualTo(3);          // head untouched
        assertThat(store.readAfter("c", 0, 100))
                .extracting(Channel.Event::seq).containsExactly(3L);
    }
}
