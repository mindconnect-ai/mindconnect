package ai.mindconnect.agentrest.stream;

import java.time.Duration;
import java.util.function.Consumer;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;

/**
 * The stream of a session a client reconnects to: every frame of every turn
 * in it, numbered and buffered, so a client that names the last number it
 * saw is replayed what it missed and then goes on live.
 */
public final class SessionFrameChannels {

    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private final ChannelRegistry registry;

    public SessionFrameChannels(ChannelRegistry registry) {
        this.registry = registry;
    }

    /** Channels of their own, given up after ten idle minutes. */
    public static SessionFrameChannels standalone() {
        return new SessionFrameChannels(new ChannelRegistry().withIdleEviction(IDLE_EVICTION));
    }

    /** Where the frames of one turn go to reach the session's stream. */
    public Consumer<TurnFrame> publisherFor(String sessionId, String turnId) {
        return frame -> channel(sessionId).publish(new SessionFrame(turnId, frame));
    }

    public Subscription subscribe(String sessionId, long afterSeq, Consumer<Channel.Event<SessionFrame>> consumer) {
        return channel(sessionId).subscribe(afterSeq, consumer);
    }

    public long lastSeq(String sessionId) {
        return channel(sessionId).lastSeq();
    }

    public long earliestBufferedSeq(String sessionId) {
        return channel(sessionId).earliestBufferedSeq();
    }

    private Channel<SessionFrame> channel(String sessionId) {
        return registry.channel("session_frames_" + sessionId);
    }
}
