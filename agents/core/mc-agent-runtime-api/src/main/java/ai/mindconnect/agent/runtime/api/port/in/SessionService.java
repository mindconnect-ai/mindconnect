package ai.mindconnect.agent.runtime.api.port.in;

import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;

/**
 * Sessions, as far as running an agent needs them: open one, look one up,
 * branch one off, hear what happens in them. Without a handle — every call names its session, so any
 * instance of the runtime can answer it and nothing has to be kept open
 * between two calls.
 *
 * <p>Listing, renaming and deleting sessions and reading their conversation
 * is managing them, and not this API's business.
 */
public interface SessionService {

    Session open(SessionSpec spec);

    Optional<Session> find(SessionId sessionId);

    /**
     * Gives the session another agent: the turns accepted from now on run
     * as it. A turn underway or waiting keeps the agent it started with.
     *
     * @throws IllegalArgumentException if there is no such session
     */
    Session changeAgent(SessionId sessionId, AgentDefinition agent);

    /**
     * A new session that starts where this one stands: with everything said
     * so far, and from then on on its own — what is said in either is not
     * seen in the other. It is the same kind of session as its source: the
     * same user, agent and metadata, under the same parent.
     *
     * <p>A turn of the source that is still underway is not taken along. A
     * conversation that ends in the middle of a tool call cannot be
     * continued, so the fork starts after the last turn that is whole.
     */
    Session fork(SessionId sessionId);

    /**
     * Like {@link #fork}, but the new session is opened UNDER this one: it
     * starts with everything said so far and knows the session it came from
     * as its parent. What a side task needs — the whole context, and
     * somewhere to report back to.
     */
    Session forkAsChild(SessionId sessionId);

    /**
     * Hears one session as it happens, until the subscription is closed:
     * every turn in it from its beginning to its end — the answer as it is
     * written, the tool calls, a question a turn stops for — and what a
     * capability has to say about the session.
     *
     * <p>From now on: what happened before is in the session, not replayed.
     * Whether a turn runs in it at the moment of subscribing,
     * {@link TurnService#runningTurn} says. Whoever follows one turn it
     * submitted, from its first event, subscribes to the turn instead
     * ({@link TurnService#subscribe}).
     *
     * @param childSessions also every session under this one, however deep —
     *                      the sessions of its sub-agents and its forks as
     *                      children. Each event says which session and turn
     *                      it is from
     */
    Subscription stream(SessionId sessionId, boolean childSessions, SessionEventListener listener);
}
