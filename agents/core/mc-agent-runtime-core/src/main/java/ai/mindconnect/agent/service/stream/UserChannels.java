package ai.mindconnect.agent.service.stream;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * One ring-buffered channel per user, carrying the coarse {@link UserEvent}s
 * of every session that user owns. The counterpart of {@link SessionChannels}
 * one level up: a session's stream is what a client attaches to while it
 * looks at that session; the user's stream is what it keeps attached the
 * whole time, so it hears about the sessions it is not looking at.
 *
 * <p>Same delivery model as the session channels — a monotonic {@code seq}
 * per user as the reconnect cursor, replay from the buffer, then live — and
 * the same rule: the channel is delivery, never storage. A missed event
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

    private final ChannelRegistry registry;

    public UserChannels() {
        this(new ChannelRegistry().withIdleEviction(IDLE_EVICTION));
    }

    public UserChannels(ChannelRegistry registry) {
        this.registry = registry;
    }

    /** Announces {@code event} to every client attached to {@code userId}'s stream. */
    public void publish(String userId, UserEvent event) {
        if (userId == null || userId.isBlank() || event == null) return;
        channel(userId).publish(event);
    }

    /**
     * Attach to the user's stream: replay everything after {@code afterSeq}
     * from the buffer, then continue live. The event's {@code seq} is the
     * cursor for the next reconnect.
     */
    public Subscription subscribe(String userId, long afterSeq,
                                  Consumer<Channel.Event<UserEvent>> consumer) {
        return channel(userId).subscribe(afterSeq, consumer);
    }

    /** The newest sequence the user's stream has seen (0 when nothing happened). */
    public long lastSeq(String userId) {
        return channel(userId).lastSeq();
    }

    /** The oldest sequence still in the buffer — everything before it is gone. */
    public long earliestBufferedSeq(String userId) {
        return channel(userId).earliestBufferedSeq();
    }

    private Channel<UserEvent> channel(String userId) {
        return registry.channel(id(userId));
    }

    private static String id(String userId) {
        return "user_" + userId;
    }
}
