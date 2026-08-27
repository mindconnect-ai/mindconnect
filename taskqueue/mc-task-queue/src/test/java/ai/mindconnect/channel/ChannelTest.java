package ai.mindconnect.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelTest {

    private final ChannelRegistry registry = new ChannelRegistry();

    @Test
    void publishAndLiveSubscribe() throws Exception {
        Channel<String> channel = registry.channel("resp_1");
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        channel.subscribe(0, event -> { received.add(event.value()); three.countDown(); });

        channel.publish("a");
        channel.publish("b");
        channel.publish("c");

        assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly("a", "b", "c");
    }

    @Test
    void closingASubscriptionStillDeliversWhatWasAlreadyQueued() throws Exception {
        // The last event of a finished stream (a Done marker, say) must not be
        // lost to a close that follows the publish immediately: close detaches
        // from FUTURE events, queued ones still go out.
        Channel<String> channel = registry.channel("resp_graceful");
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        Subscription subscription = channel.subscribe(0, event -> {
            try {
                Thread.sleep(20);                  // slow consumer — events queue up
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // graceful close interrupts the drain
            }
            received.add(event.value());
            done.countDown();
        });

        channel.publish("a");
        channel.publish("b");
        channel.publish("done");
        subscription.close();                      // immediately after the last publish

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly("a", "b", "done");
    }

    @Test
    void lateSubscriberReplaysBufferFromAfterSeq() throws Exception {
        Channel<String> channel = registry.channel("resp_2");
        channel.publish("a");                       // seq 1
        long seen = channel.publish("b");           // seq 2
        channel.publish("c");                       // seq 3

        List<Long> seqs = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        channel.subscribe(seen - 1, event -> { seqs.add(event.seq()); two.countDown(); });

        assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seqs).containsExactly(2L, 3L);   // replay b + c, no duplicates, no gap
    }

    @Test
    void replayThenLiveKeepsTotalOrder() throws Exception {
        Channel<String> channel = registry.channel("resp_3");
        channel.publish("old");

        List<Long> seqs = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        channel.subscribe(0, event -> { seqs.add(event.seq()); two.countDown(); });
        channel.publish("new");

        assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seqs).containsExactly(1L, 2L);
    }

    @Test
    void brokenConsumerNeverBreaksTheStream() throws Exception {
        Channel<String> channel = registry.channel("resp_4");
        List<String> healthy = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        channel.subscribe(0, event -> { throw new IllegalStateException("broken observer"); });
        channel.subscribe(0, event -> { healthy.add(event.value()); two.countDown(); });

        channel.publish("a");
        channel.publish("b");

        assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(healthy).containsExactly("a", "b");
    }

    @Test
    void ringBufferDropsOldest() {
        ChannelRegistry tiny = new ChannelRegistry(3, 16);
        Channel<String> channel = tiny.channel("resp_5");
        for (int i = 1; i <= 10; i++) channel.publish("e" + i);

        assertThat(channel.lastSeq()).isEqualTo(10);
        assertThat(channel.earliestBufferedSeq()).isEqualTo(8);   // only the tail remains
    }

    @Test
    void closedSubscriptionStopsDelivery() throws Exception {
        Channel<String> channel = registry.channel("resp_6");
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        Subscription subscription = channel.subscribe(0,
                event -> { received.add(event.value()); first.countDown(); });

        channel.publish("a");
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.close();
        channel.publish("b");

        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(received).containsExactly("a");
    }

    @Test
    void publisherIsNotBlockedByASlowConsumer() throws Exception {
        ChannelRegistry small = new ChannelRegistry(2048, 4);   // tiny subscriber queue
        Channel<String> channel = small.channel("resp_7");
        CountDownLatch blocked = new CountDownLatch(1);
        channel.subscribe(0, event -> {
            try {
                blocked.await();                  // consumer hangs forever
            } catch (InterruptedException ignored) { }
        });

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) channel.publish("e" + i);   // must overflow, not block
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMs).isLessThan(1000);
        assertThat(channel.lastSeq()).isEqualTo(1000);
        blocked.countDown();
    }

    @Test
    void lazyMaterializationAndRemove() {
        assertThat(registry.find("resp_8")).isEmpty();
        Channel<String> channel = registry.channel("resp_8");     // materializes
        assertThat(registry.<String>find("resp_8")).containsSame(channel);

        registry.remove("resp_8");
        assertThat(registry.find("resp_8")).isEmpty();
        assertThat(registry.channel("resp_8")).isNotSameAs(channel);   // fresh on next use
    }

    @Test
    void seqIsStrictlyIncreasingUnderConcurrentPublish() throws Exception {
        Channel<String> channel = registry.channel("resp_9");
        AtomicLong count = new AtomicLong();
        int threads = 8, perThread = 500;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < perThread; i++) { channel.publish("x"); count.incrementAndGet(); }
                done.countDown();
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(channel.lastSeq()).isEqualTo(threads * perThread);
    }

    @Test
    void idleChannelsAreEvictedActiveOnesSurvive() throws Exception {
        Channel<String> idle = registry.channel("resp_idle");
        idle.publish("once");

        Channel<String> watched = registry.channel("resp_watched");
        watched.subscribe(0, event -> { });                        // has a subscriber

        TimeUnit.MILLISECONDS.sleep(80);
        Channel<String> fresh = registry.channel("resp_fresh");    // recent activity
        fresh.publish("now");

        int evicted = registry.evictIdle(java.time.Duration.ofMillis(50));

        assertThat(evicted).isEqualTo(1);
        assertThat(registry.find("resp_idle")).isEmpty();          // gone — reclaimable
        assertThat(registry.find("resp_watched")).isPresent();     // subscriber protects it
        assertThat(registry.find("resp_fresh")).isPresent();       // activity protects it

        // "waking up" is just the next access — lazy re-materialization
        Channel<String> reborn = registry.channel("resp_idle");
        assertThat(reborn.lastSeq()).isZero();                     // seq restarts: clients
        assertThat(reborn).isNotSameAs(idle);                      // cursor on DOMAIN seq
    }

    @Test
    void subscriptionFilterSelectsButSeqStaysIntact() throws Exception {
        Channel<String> channel = registry.channel("resp_filter");
        List<String> lifecycleOnly = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        channel.subscribe(0, value -> !value.startsWith("token:"),
                event -> { lifecycleOnly.add(event.seq() + ":" + event.value()); two.countDown(); });

        channel.publish("item-added");        // seq 1
        channel.publish("token:Lis");         // seq 2 — filtered out
        channel.publish("token:bon");         // seq 3 — filtered out
        channel.publish("item-done");         // seq 4

        assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
        // selection, not renumbering: the client still cursors on the real seq
        assertThat(lifecycleOnly).containsExactly("1:item-added", "4:item-done");
    }

    @Test
    void registryListenerSeesMaterializeAndEvict() throws Exception {
        List<String> lifecycle = new CopyOnWriteArrayList<>();
        registry.addListener(new ChannelLifecycleListener() {
            @Override public void onMaterialized(String id) { lifecycle.add("up:" + id); }
            @Override public void onEvicted(String id) { lifecycle.add("down:" + id); }
        });

        registry.channel("resp_ops").publish("x");
        TimeUnit.MILLISECONDS.sleep(60);
        registry.evictIdle(java.time.Duration.ofMillis(30));
        registry.remove("resp_never_seen");           // no channel — no event

        assertThat(lifecycle).containsExactly("up:resp_ops", "down:resp_ops");
    }
}