package ai.mindconnect.channel;

import ai.mindconnect.channel.memory.InMemoryChannelStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentChannelTest {

    private final InMemoryChannelStore<String> store = new InMemoryChannelStore<>();
    private final PersistentChannels<String> channels =
            new PersistentChannels<>(store, new ChannelRegistry());

    @Test
    void publishIsDurableAndLive() throws Exception {
        PersistentChannel<String> main = channels.channel("resp_1");
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        main.subscribe(0, event -> { received.add(event.seq() + ":" + event.value()); two.countDown(); });

        main.publish("item-added");
        main.publish("item-done");

        assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly("1:item-added", "2:item-done");
        assertThat(store.lastSeq("resp_1")).isEqualTo(2);          // the truth is in the store
    }

    @Test
    void lateSubscriberReplaysFromStoreNotFromBuffer() {
        PersistentChannel<String> main = channels.channel("resp_2");
        main.publish("a");
        main.publish("b");
        channels.remove("resp_2");                                 // crash/eviction: live side gone

        PersistentChannel<String> reborn = channels.channel("resp_2");
        List<String> received = new CopyOnWriteArrayList<>();
        reborn.subscribe(0, event -> received.add(event.seq() + ":" + event.value()));

        // replay is synchronous and complete although the live channel is fresh
        assertThat(received).containsExactly("1:a", "2:b");
    }

    @Test
    void seqSpaceSurvivesRematerialization() throws Exception {
        PersistentChannel<String> main = channels.channel("resp_3");
        main.publish("a");                                         // seq 1
        channels.remove("resp_3");

        PersistentChannel<String> reborn = channels.channel("resp_3");
        List<Long> seqs = new CopyOnWriteArrayList<>();
        CountDownLatch second = new CountDownLatch(1);
        reborn.subscribe(1, event -> { seqs.add(event.seq()); second.countDown(); });   // client cursor: 1
        reborn.publish("b");                                       // continues at seq 2, not 1!

        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seqs).containsExactly(2L);                      // no duplicate, no reset
    }

    @Test
    void reconnectBridgeHasNoGapAndNoDuplicate() throws Exception {
        PersistentChannel<String> main = channels.channel("resp_4");
        for (int i = 1; i <= 5; i++) main.publish("e" + i);

        List<Long> seqs = new CopyOnWriteArrayList<>();
        CountDownLatch live = new CountDownLatch(3);               // replay 4,5 + live 6
        main.subscribe(3, event -> { seqs.add(event.seq()); live.countDown(); });   // client had seen 1..3
        main.publish("e6");

        assertThat(live.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seqs).containsExactly(4L, 5L, 6L);
    }

    @Test
    void mainChannelDurableSubChannelEphemeral() throws Exception {
        // the split from the discussion: items durable, tokens ephemeral
        ChannelRegistry deltas = new ChannelRegistry();
        PersistentChannel<String> main = channels.channel("resp_5");
        Channel<String> tokens = deltas.channel("resp_5#deltas");

        List<String> tokenSeen = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        tokens.subscribe(0, event -> { tokenSeen.add(event.value()); two.countDown(); });

        tokens.publish("Lis");
        tokens.publish("bon");
        main.publish("message-item: Lisbon");

        assertThat(two.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tokenSeen).containsExactly("Lis", "bon");
        assertThat(store.lastSeq("resp_5")).isEqualTo(1);          // only the item is durable
        assertThat(store.lastSeq("resp_5#deltas")).isZero();       // tokens never touch the store
        assertThat(deltas.evictIdle(Duration.ZERO)).isZero();      // subscriber still attached
    }
}
