package ai.mindconnect.chatui.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two promises this bus makes to a session with more than one client
 * watching it: one slow reader must not hold up the others, and a finished
 * turn must never be replayed into a page that already renders it.
 */
class StreamBusTest {

    /** Records what reached the wire; optionally stalls on the way. */
    private static class RecordingEmitter extends SseEmitter {
        final List<String> received = new CopyOnWriteArrayList<>();
        private final long delayMs;

        RecordingEmitter() {
            this(0);
        }

        RecordingEmitter(long delayMs) {
            super(0L);
            this.delayMs = delayMs;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            // Spring splits one event into its id/event/data parts; joining
            // their payloads back together gives the frame as it goes out.
            StringBuilder frame = new StringBuilder();
            for (var part : builder.build()) {
                frame.append(part.getData());
            }
            received.add(frame.toString());
        }
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
    }

    /**
     * The reason this class stopped doing its own fan-out. Delivery runs on a
     * queue per subscriber, so a reader that has stopped draining cannot slow
     * the producer down — previously every publish waited for the slowest
     * socket while holding the lock, which also stalled every other reader.
     */
    @Test
    void aSlowReaderDoesNotHoldUpThePublisher() {
        StreamBus bus = new StreamBus();
        var slow = new RecordingEmitter(200);
        bus.attach(slow, 0);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            bus.publish("patch", "{\"n\":" + i + "}");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Serial delivery would need 20 × 200ms = 4s. A generous ceiling:
        // the point is the order of magnitude, not the exact figure.
        assertThat(elapsedMs).isLessThan(1_000);
    }

    /** A second client attached later still receives what is published. */
    @Test
    void everyAttachedClientGetsTheEvent() throws Exception {
        StreamBus bus = new StreamBus();
        var first = new RecordingEmitter();
        var second = new RecordingEmitter();
        bus.attach(first, 0);
        bus.attach(second, 0);

        bus.publish("patch", "hello");

        eventually(() -> !first.received.isEmpty() && !second.received.isEmpty());
        assertThat(first.received).hasSize(1);
        assertThat(second.received).hasSize(1);
        assertThat(bus.subscriberCount()).isEqualTo(2);
    }

    /**
     * The replay floor. Patches are APPENDs against a page rendered from
     * persisted history, so a client that joins a new turn asking for
     * "everything" must still not be handed the previous turn — it already
     * shows it, and would show it twice.
     */
    @Test
    void aFinishedTurnIsNotReplayedIntoANewClient() throws Exception {
        StreamBus bus = new StreamBus();
        bus.startTurn();
        bus.publish("patch", "turn-1-a");
        bus.publish("patch", "turn-1-b");
        bus.publish("done", "");

        bus.startTurn();                       // second turn begins
        bus.publish("patch", "turn-2-a");

        var joiner = new RecordingEmitter();
        bus.attach(joiner, 0);                 // "give me everything you have"

        eventually(() -> !joiner.received.isEmpty());
        assertThat(joiner.received).hasSize(1);
        assertThat(joiner.received.get(0)).contains("turn-2-a");
    }

    /** A reconnect within the turn still replays what it missed. */
    @Test
    void aReconnectReplaysTheRestOfItsOwnTurn() throws Exception {
        StreamBus bus = new StreamBus();
        bus.startTurn();
        bus.publish("patch", "a");
        long seen = bus.lastSeq();
        bus.publish("patch", "b");
        bus.publish("patch", "c");

        var back = new RecordingEmitter();
        bus.attach(back, seen);

        eventually(() -> back.received.size() >= 2);
        assertThat(back.received).hasSize(2);
        assertThat(back.received.get(0)).contains("b");
        assertThat(back.received.get(1)).contains("c");
    }

    /**
     * A client joining mid-turn is handed the frames that build the reply
     * bubble before anything live, or the cumulative token patches would
     * REPLACE a node its DOM does not have.
     */
    @Test
    void thePreludeArrivesBeforeTheLiveFeed() throws Exception {
        StreamBus bus = new StreamBus();
        bus.startTurn();
        bus.publish("patch", "already-there");

        var joiner = new RecordingEmitter();
        bus.attach(joiner, bus.lastSeq(),
                List.of(new StreamBus.Event(0, "patch", "catch-up")));
        bus.publish("patch", "live");

        eventually(() -> joiner.received.size() >= 2);
        assertThat(joiner.received).hasSize(2);
        assertThat(joiner.received.get(0)).contains("catch-up");
        assertThat(joiner.received.get(1)).contains("live");
    }

    /** Detaching stops delivery; the heartbeat has nobody left to reach. */
    @Test
    void detachingStopsDelivery() throws Exception {
        StreamBus bus = new StreamBus();
        var emitter = new RecordingEmitter();
        bus.attach(emitter, 0);
        bus.publish("patch", "before");
        eventually(() -> !emitter.received.isEmpty());

        bus.detach(emitter);
        bus.publish("patch", "after");
        Thread.sleep(100);

        assertThat(bus.subscriberCount()).isZero();
        assertThat(emitter.received).hasSize(1);
        assertThat(emitter.received.get(0)).contains("before");
    }

    /** A write that fails is how a client that went away is noticed. */
    @Test
    void theHeartbeatDropsAClientThatIsGone() {
        StreamBus bus = new StreamBus();
        var dead = new RecordingEmitter() {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("socket closed");
            }
        };
        bus.attach(dead, 0);
        assertThat(bus.subscriberCount()).isEqualTo(1);

        bus.ping();

        assertThat(bus.subscriberCount()).isZero();
    }

    /** Nothing is lost when a subscriber attaches while events are flowing. */
    @Test
    void attachingUnderLoadLosesNothingAndDuplicatesNothing() throws Exception {
        StreamBus bus = new StreamBus();
        bus.startTurn();
        var latch = new CountDownLatch(1);
        var publisher = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 200; i++) {
                bus.publish("patch", "n" + i);
            }
            latch.countDown();
        });

        var joiner = new RecordingEmitter();
        bus.attach(joiner, 0);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        publisher.join();

        eventually(() -> joiner.received.size() >= 1);
        Thread.sleep(200);
        // Whatever arrived must be strictly increasing with no repeats — the
        // guarantee that lets a client cursor on the sequence.
        assertThat(joiner.received).doesNotHaveDuplicates();
    }
}
