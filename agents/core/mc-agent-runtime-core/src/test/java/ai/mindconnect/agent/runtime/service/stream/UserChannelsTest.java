package ai.mindconnect.agent.runtime.service.stream;

import ai.mindconnect.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class UserChannelsTest {

    private final UserChannels channels = new UserChannels();
    private final UUID session = UUID.randomUUID();
    private final UUID turn = UUID.randomUUID();

    @Test
    void eventsReachOnlyTheirUsersStream() {
        List<Channel.Event<UserEvent>> alice = new CopyOnWriteArrayList<>();
        List<Channel.Event<UserEvent>> bob = new CopyOnWriteArrayList<>();
        channels.subscribe("alice", 0, alice::add);
        channels.subscribe("bob", 0, bob::add);

        channels.publish("alice", new UserEvent.TurnStarted(session, turn));
        channels.publish("alice", new UserEvent.TurnFinished(session, turn, UserEvent.TurnOutcome.COMPLETED));

        awaitSize(alice, 2);
        assertThat(alice.get(0).value()).isInstanceOf(UserEvent.TurnStarted.class);
        assertThat(alice.get(1).value()).isInstanceOf(UserEvent.TurnFinished.class);
        assertThat(alice.get(1).seq()).isGreaterThan(alice.get(0).seq());
        assertThat(bob).isEmpty();
    }

    @Test
    void afterSeqReplaysTheBufferedTailThenContinuesLive() {
        channels.publish("alice", new UserEvent.SessionStarted(session, UUID.randomUUID()));
        channels.publish("alice", new UserEvent.TurnStarted(session, turn));
        long cursor = channels.lastSeq("alice") - 1;   // "I saw the session start"

        List<Channel.Event<UserEvent>> seen = new CopyOnWriteArrayList<>();
        channels.subscribe("alice", cursor, seen::add);
        channels.publish("alice", new UserEvent.SessionTitled(session, "Hello"));

        awaitSize(seen, 2);
        assertThat(seen.get(0).value()).isInstanceOf(UserEvent.TurnStarted.class);
        assertThat(seen.get(1).value()).isEqualTo(new UserEvent.SessionTitled(session, "Hello"));
    }

    @Test
    void aSessionWithoutAnOwnerIsNotAnnounced() {
        channels.publish(null, new UserEvent.TurnStarted(session, turn));
        channels.publish("", new UserEvent.TurnStarted(session, turn));
        assertThat(channels.lastSeq("")).isZero();
    }

    private static void awaitSize(List<?> list, int size) {
        long deadline = System.currentTimeMillis() + 2000;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(list).hasSize(size);
    }
}
