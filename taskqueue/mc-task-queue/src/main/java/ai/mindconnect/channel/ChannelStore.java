package ai.mindconnect.channel;

import java.time.Instant;
import java.util.List;

/**
 * Persistence port for a durable channel — the "ChannelRepo": an append-only,
 * sequenced event log per channel id. {@code append} assigns the DOMAIN seq;
 * live delivery mirrors it, so clients cursor on one seq space.
 *
 * <p><b>One truth rule:</b> where the events already ARE domain data (an
 * agent response's items live in the conversation store), the implementation
 * is an ADAPTER over that store — never a second table holding the same
 * content. The bundled {@link ai.mindconnect.channel.memory.InMemoryChannelStore}
 * is for tests and for events that have no other home.
 */
public interface ChannelStore<E> {

    /** Durably appends and returns the assigned seq (strictly increasing per id). */
    long append(String channelId, E value);

    /** Events with {@code seq > afterSeq}, in order, at most {@code limit}. */
    List<Channel.Event<E>> readAfter(String channelId, long afterSeq, int limit);

    long lastSeq(String channelId);

    /**
     * Retention: forgets events with {@code seq < beforeSeq} and returns how
     * many were dropped. The head ({@link #lastSeq}) is untouched — the seq
     * space never restarts, a late subscriber simply replays from the oldest
     * event still kept. Like {@link ai.mindconnect.taskqueue.TaskStore#purgeTerminal},
     * this runs when someone asks — {@link PersistentChannels#withRetention}
     * is that someone, configured once by the operator.
     */
    int purgeBefore(String channelId, long beforeSeq);

    /**
     * Time-based retention: forgets events appended before {@code before}.
     * Same contract as {@link #purgeBefore} — the head survives, the seq
     * space never restarts. The timestamp is the STORE's append clock, not
     * part of the event: age is a storage fact, so the store keeps it.
     */
    int purgeOlderThan(String channelId, Instant before);
}
