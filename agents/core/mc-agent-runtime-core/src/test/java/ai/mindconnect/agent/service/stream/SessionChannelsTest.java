package ai.mindconnect.agent.service.stream;

import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.channel.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SessionChannelsTest {

    private final SessionChannels channels = new SessionChannels();
    private final UUID session = UUID.randomUUID();
    private final UUID turnA = UUID.randomUUID();
    private final UUID turnB = UUID.randomUUID();

    @Test
    void publisherStampsTurnCoordinatesOnEveryEvent() {
        List<Channel.Event<SessionEvent>> seen = new CopyOnWriteArrayList<>();
        channels.subscribe(session, 0, seen::add);

        channels.publisherFor(session, turnA, 0).accept(new StreamEvent.Token("hi"));
        channels.publisherFor(session, turnB, 2).accept(new StreamEvent.Token("ho"));

        awaitSize(seen, 2);
        assertThat(seen.get(0).value().turnId()).isEqualTo(turnA);
        assertThat(seen.get(0).value().run()).isZero();
        assertThat(seen.get(1).value().turnId()).isEqualTo(turnB);
        assertThat(seen.get(1).value().run()).isEqualTo(2);
        assertThat(seen.get(1).seq()).isGreaterThan(seen.get(0).seq());
    }

    @Test
    void afterSeqReplaysTheBufferedTailThenContinuesLive() {
        var publish = channels.publisherFor(session, turnA, 0);
        publish.accept(new StreamEvent.Token("one"));
        publish.accept(new StreamEvent.Token("two"));
        long cursor = channels.lastSeq(session) - 1;   // "I saw everything up to 'one'"

        List<Channel.Event<SessionEvent>> seen = new CopyOnWriteArrayList<>();
        channels.subscribe(session, cursor, seen::add);
        publish.accept(new StreamEvent.Token("three"));

        awaitSize(seen, 2);
        assertThat(tokens(seen)).containsExactly("two", "three");
    }

    @Test
    void turnViewFiltersTheSharedStreamAndSkipsHistory() {
        channels.publisherFor(session, turnA, 0).accept(new StreamEvent.Token("before"));

        List<StreamEvent> seen = new CopyOnWriteArrayList<>();
        channels.subscribeTurn(session, turnB, seen::add);

        channels.publisherFor(session, turnA, 0).accept(new StreamEvent.Token("other turn"));
        channels.publisherFor(session, turnB, 0).accept(new StreamEvent.Token("mine"));

        awaitSize(seen, 1);
        assertThat(((StreamEvent.Token) seen.get(0)).text()).isEqualTo("mine");
    }

    private static List<String> tokens(List<Channel.Event<SessionEvent>> events) {
        return events.stream()
                .map(e -> ((StreamEvent.Token) e.value().event()).text())
                .toList();
    }

    private static void awaitSize(List<?> list, int size) {
        long deadline = System.currentTimeMillis() + 2000;
        while (list.size() < size && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(list).hasSize(size);
    }
}
