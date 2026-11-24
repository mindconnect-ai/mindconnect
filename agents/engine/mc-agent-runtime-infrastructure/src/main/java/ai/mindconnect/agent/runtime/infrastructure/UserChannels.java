package ai.mindconnect.agent.runtime.infrastructure;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * One ring-buffered channel per user, carrying the coarse {@link UserEvent}s
 * of every session that user owns. A session's stream is what a client
 * attaches to while it looks at that session; the user's stream is what it
 * keeps attached the whole time, so it hears about the sessions it is not
 * looking at.
 *
 * <p>A monotonic {@code seq} per user is the reconnect cursor: replay from
 * the buffer, then live. The channel is delivery, never storage. A missed event
 * costs a badge update, not data; the persisted sessions and approvals are
 * the truth a client reloads from. Channels nobody listens to are evicted
 * after idling {@link #IDLE_EVICTION}.
 *
 * <p>Publishing is a fire-and-forget from inside a turn or a session
 * operation and must never fail that operation: a {@code null} user (a
 * session without an owner) is simply not announced.
 */
public final class UserChannels {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final ChannelRegistry registry = new ChannelRegistry().withIdleEviction(IDLE_EVICTION);

    /** Announces {@code event} to every client attached to {@code userId}'s stream. */
    public void publish(UserId userId, UserEvent event) {
        if (userId == null || event == null) return;
        channel(userId).publish(event);
    }

    /**
     * Attach to the user's stream: replay everything after {@code afterSeq}
     * from the buffer, then continue live. The event's {@code seq} is the
     * cursor for the next reconnect.
     */
    public Subscription subscribe(UserId userId, long afterSeq,
                                  Consumer<Channel.Event<UserEvent>> consumer) {
        return channel(userId).subscribe(afterSeq, consumer);
    }

    /** The newest sequence the user's stream has seen (0 when nothing happened). */
    public long lastSeq(UserId userId) {
        return channel(userId).lastSeq();
    }

    /** The oldest sequence still in the buffer — everything before it is gone. */
    public long earliestBufferedSeq(UserId userId) {
        return channel(userId).earliestBufferedSeq();
    }

    private Channel<UserEvent> channel(UserId userId) {
        return registry.channel(id(userId));
    }

    private static String id(UserId userId) {
        return "user_" + userId.value();
    }
}
