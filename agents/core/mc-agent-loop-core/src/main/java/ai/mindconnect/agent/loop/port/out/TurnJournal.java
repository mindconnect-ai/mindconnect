package ai.mindconnect.agent.loop.port.out;

import java.util.List;

import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnProgress;

/**
 * Everything a turn needs to go on, in one place: the conversation it writes
 * into, how far it has come, and whether it was told to stop. An instance is
 * bound to one session and one turn — the adapter is a few lines over the
 * conversation store, closing over what only that turn knows (the senders,
 * the store's keys); a test is a list.
 *
 * <p>The loop keeps nothing between two calls. A turn that waited for a tool
 * is resumed from what this journal says, possibly by another process.
 *
 * <p>This is also where storage meets {@link TurnMessage}: the adapter maps
 * its stored messages into it on {@link #load} and out of it on
 * {@link #append}, serialising tool calls and results into whatever shape
 * its store keeps. {@code turnId} and metadata go in and come back
 * unchanged — the loop reads its own state from them.
 */
public interface TurnJournal {

    /**
     * The session's conversation, oldest first. Loading less than all of it
     * is a cost optimisation with one safety rule: the cut must never split
     * an open call pair — cutting at turn boundaries is safe, since turn
     * boundaries close calls. Within the running turn, shorten messages but
     * never remove one: openness and progress are counted from them.
     */
    List<TurnMessage> load();

    /**
     * Persists what one step of the turn produced and returns the messages
     * as stored, each with its id. One call per step, so an implementation
     * can write the step atomically. The messages already carry their turn
     * and, where a model call produced them, its usage.
     */
    List<TurnMessage> append(List<TurnMessage> messages);

    /**
     * The model rounds this turn has turned and what they cost, counted from
     * the messages. An implementation that keeps its own counters — updated
     * in {@link #append} from {@link TurnMessage#usage()} — answers from
     * those instead.
     */
    default TurnProgress progress() {
        return TurnProgress.of(ToolCalls.episode(load()));
    }

    /**
     * Whether this turn was told to stop. Asked between rounds, and what
     * reaches a turn that is waiting and holds no thread: the next resume
     * ends at once. Whoever cancels a turn marks it here first.
     */
    boolean cancelRequested();
}
