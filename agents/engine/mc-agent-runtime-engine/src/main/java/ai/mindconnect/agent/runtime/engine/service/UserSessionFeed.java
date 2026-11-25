package ai.mindconnect.agent.runtime.engine.service;

import java.util.function.Consumer;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;

/**
 * The lifecycle of a user's sessions with the numbers the engine's channel
 * gives its events — for a client that comes back and names the last number
 * it saw. The runtime API tells of sessions from now on and knows no
 * numbers; this is the engine's own, for a server that sits on this engine.
 *
 * <p>A user's sessions in every namespace: a user is one person across the
 * installation, and what they follow is a list of their sessions.
 */
public interface UserSessionFeed {

    /**
     * @param seq              the event's number; what a client hands back to go on after it
     * @param namespace        the namespace the session is in
     * @param underAnotherTurn whether the event belongs to a turn another turn started — a sub-agent's
     */
    record Entry(long seq, String namespace, Session session, boolean underAnotherTurn, SessionEvent event) { }

    /** Tells what is still kept after {@code afterSeq}, then what happens from now on. */
    Subscription follow(UserId user, long afterSeq, Consumer<Entry> listener);

    /** The number of the newest event; 0 when nothing has happened. */
    long latestSeq();

    /** The number of the oldest event still kept: what came before it is gone. */
    long earliestKeptSeq();
}
