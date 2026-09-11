package ai.mindconnect.agent.runtime.service.stream;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.message.domain.ChatTurnId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserChannelsTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private final UserChannels channels = new UserChannels();
    private final SessionId session = SessionId.random();
    private final ChatTurnId turn = ChatTurnId.random();

    @Test
    void eventsReachOnlyTheirUsersStream() {
        List<Channel.Event<UserEvent>> alice = new CopyOnWriteArrayList<>();
        List<Channel.Event<UserEvent>> bob = new CopyOnWriteArrayList<>();
        channels.subscribe(ALICE, 0, alice::add);
        channels.subscribe(BOB, 0, bob::add);

        channels.publish(ALICE, new UserEvent.TurnStarted(session, turn));
        channels.publish(ALICE, new UserEvent.TurnFinished(session, turn, UserEvent.TurnOutcome.COMPLETED));

        awaitSize(alice, 2);
        assertThat(alice.get(0).value()).isInstanceOf(UserEvent.TurnStarted.class);
        assertThat(alice.get(1).value()).isInstanceOf(UserEvent.TurnFinished.class);
        assertThat(alice.get(1).seq()).isGreaterThan(alice.get(0).seq());
        assertThat(bob).isEmpty();
    }

    @Test
    void afterSeqReplaysTheBufferedTailThenContinuesLive() {
        channels.publish(ALICE, new UserEvent.SessionStarted(session, AgentId.random()));
        channels.publish(ALICE, new UserEvent.TurnStarted(session, turn));
        long cursor = channels.lastSeq(ALICE) - 1;   // "I saw the session start"

        List<Channel.Event<UserEvent>> seen = new CopyOnWriteArrayList<>();
        channels.subscribe(ALICE, cursor, seen::add);
        channels.publish(ALICE, new UserEvent.SessionTitled(session, "Hello"));

        awaitSize(seen, 2);
        assertThat(seen.get(0).value()).isInstanceOf(UserEvent.TurnStarted.class);
        assertThat(seen.get(1).value()).isEqualTo(new UserEvent.SessionTitled(session, "Hello"));
    }

    @Test
    void aSessionWithoutAnOwnerIsNotAnnounced() {
        List<Channel.Event<UserEvent>> alice = new CopyOnWriteArrayList<>();
        channels.subscribe(ALICE, 0, alice::add);

        // No owner: dropped silently, nobody's stream moves.
        channels.publish(null, new UserEvent.TurnStarted(session, turn));

        assertThat(channels.lastSeq(ALICE)).isZero();
        assertThat(alice).isEmpty();
        // A blank owner used to be the other way to say "nobody"; the typed id
        // refuses to exist, so it can no longer reach the channel at all.
        assertThatThrownBy(() -> UserId.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitSize(List<?> list, int size) {
        long deadline = System.currentTimeMillis() + 2000;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(list).hasSize(size);
    }
}
