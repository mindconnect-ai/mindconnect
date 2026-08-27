package ai.mindconnect.channel.memory;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reference store for tests and for events without another durable home.
 * Real deployments back this with a table — or, for agent responses, with
 * an ADAPTER over the conversation item store (one truth rule).
 */
public final class InMemoryChannelStore<E> implements ChannelStore<E> {

    /** The event plus its append time — age is a storage fact, so the store
     * keeps it here instead of forcing a timestamp into every event. */
    private record Stamped<E>(Channel.Event<E> event, Instant at) {}

    private final Clock clock;
    private final Map<String, List<Stamped<E>>> logs = new HashMap<>();

    public InMemoryChannelStore() {
        this(Clock.systemUTC());
    }

    public InMemoryChannelStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized long append(String channelId, E value) {
        List<Stamped<E>> log = logs.computeIfAbsent(channelId, key -> new ArrayList<>());
        long seq = log.isEmpty() ? 1 : log.get(log.size() - 1).event().seq() + 1;
        log.add(new Stamped<>(new Channel.Event<>(seq, value), clock.instant()));
        return seq;
    }

    @Override
    public synchronized List<Channel.Event<E>> readAfter(String channelId, long afterSeq, int limit) {
        List<Stamped<E>> log = logs.getOrDefault(channelId, List.of());
        List<Channel.Event<E>> result = new ArrayList<>();
        for (Stamped<E> stamped : log) {
            if (stamped.event().seq() > afterSeq) {
                result.add(stamped.event());
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    @Override
    public synchronized int purgeBefore(String channelId, long beforeSeq) {
        List<Stamped<E>> log = logs.getOrDefault(channelId, List.of());
        int before = log.size();
        log.removeIf(stamped -> stamped.event().seq() < beforeSeq);
        return before - log.size();
    }

    @Override
    public synchronized int purgeOlderThan(String channelId, Instant before) {
        List<Stamped<E>> log = logs.getOrDefault(channelId, List.of());
        int count = log.size();
        log.removeIf(stamped -> stamped.at().isBefore(before));
        return count - log.size();
    }

    @Override
    public synchronized long lastSeq(String channelId) {
        List<Stamped<E>> log = logs.getOrDefault(channelId, List.of());
        return log.isEmpty() ? 0 : log.get(log.size() - 1).event().seq();
    }
}
