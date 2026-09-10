package ai.mindconnect.agentrest.service;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * One channel per transcription job — where its events are published and
 * where a client attaches to watch them.
 *
 * <p>The same shape the runtime uses for a session's turn events: a
 * {@link ChannelRegistry} gives 0..n subscribers, replay from a buffer for
 * anyone who joins late or reconnects, and a bounded queue per subscriber so
 * a slow reader can never hold the producer up. Channels that nobody has
 * touched for a while are evicted.
 *
 * <p>The channel id is public — it comes back with the job — but the id is a
 * name, not a permission: the endpoint that serves the stream is the place
 * that checks who may read it.
 */
@Component
public final class TranscriptionChannels {

    /** An idle job's channel is worth nothing after this; the result is in the task. */
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(30);

    private final ChannelRegistry registry;

    public TranscriptionChannels() {
        this(new ChannelRegistry().withIdleEviction(IDLE_EVICTION));
    }

    public TranscriptionChannels(ChannelRegistry registry) {
        this.registry = registry;
    }

    /** The channel a job publishes on, derived from its task id. */
    public static String channelId(String taskId) {
        return "transcription-" + taskId;
    }

    /** Publishes one event of this job. Never blocks, never throws at the caller. */
    public void publish(String taskId, TranscriptionEvent event) {
        registry.<TranscriptionEvent>channel(channelId(taskId)).publish(event);
    }

    /**
     * Attaches to a job's channel: everything after {@code afterSeq} from the
     * buffer first, then live. The event's sequence number is the cursor for
     * the next attach, so a dropped connection resumes without a gap.
     */
    public Subscription subscribe(String taskId, long afterSeq,
                                  Consumer<Channel.Event<TranscriptionEvent>> consumer) {
        return registry.<TranscriptionEvent>channel(channelId(taskId)).subscribe(afterSeq, consumer);
    }

    /** The newest sequence this job has published (0 when nothing has happened). */
    public long lastSeq(String taskId) {
        return registry.<TranscriptionEvent>channel(channelId(taskId)).lastSeq();
    }
}
