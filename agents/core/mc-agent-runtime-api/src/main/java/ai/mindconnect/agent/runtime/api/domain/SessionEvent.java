package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * What happens in a session, in two families a listener subscribes to
 * apart: the {@link Lifecycle} of its turns — few events, enough to follow
 * many sessions at once — and the {@link Stream} of everything a turn writes,
 * for whoever shows one session as it happens.
 *
 * <p>Every event names its session and, where it belongs to one, its turn, so a listener that hears
 * several sessions — all of a user's, or a session with the sessions under
 * it — can tell them apart.
 */
public sealed interface SessionEvent {

    SessionId sessionId();

    TurnId turnId();

    /**
     * Turns beginning, asking and ending, and what capabilities say about a
     * session: which session is busy, which waits for an answer. A handful
     * of events per turn.
     */
    sealed interface Lifecycle extends SessionEvent { }

    /**
     * Messages, tool calls and what a turn reports as it runs, fragments of
     * the answer included: thousands of events per turn.
     */
    sealed interface Stream extends SessionEvent { }

    /**
     * What a turn of the session reports, as the agent loop says it: the
     * fragments of the answer and of the reasoning, every message as it is
     * written, the outcome. The other events are the digest of these.
     */
    record TurnReported(SessionId sessionId, TurnId turnId, TurnEvent event) implements Stream { }

    /** A turn began to run. */
    record TurnStarted(SessionId sessionId, TurnId turnId, String agentName) implements Lifecycle { }

    /**
     * A turn stopped because it has to ask, and has come back: nothing more
     * happens in it until it is resumed with answers.
     *
     * @param interruptions what this turn asks
     */
    record TurnInterrupted(SessionId sessionId, TurnId turnId, List<Interruption> interruptions)
            implements Lifecycle {

        public TurnInterrupted {
            interruptions = List.copyOf(interruptions);
        }
    }

    /** A turn is over — completed, incomplete, cancelled or failed. */
    record TurnFinished(SessionId sessionId, TurnId turnId, TurnStatus status) implements Lifecycle { }

    /** The agent has begun to answer. The answer follows as a {@link MessageFinished}. */
    record MessageStarted(SessionId sessionId, TurnId turnId) implements Stream { }

    /** A message is in the conversation — what the user said, or what the agent answered. */
    record MessageFinished(SessionId sessionId, TurnId turnId, TurnMessage message) implements Stream { }

    /** A tool call the model asked for was taken up. */
    record ToolCallStarted(SessionId sessionId, TurnId turnId, String callId, String toolName)
            implements Stream { }

    /** A tool call has its result; {@code failed} says whether the model was told it went wrong. */
    record ToolCallFinished(SessionId sessionId, TurnId turnId, String callId, String toolName, boolean failed)
            implements Stream { }

    /**
     * Something a capability has to say about a session: a title was found,
     * the history was summarised. Open on purpose — a new capability must
     * not need a new kind of event in the API, and a listener that does not
     * know a kind passes it by.
     *
     * @param turnId  the turn it happened in, or {@code null} when it
     *                belongs to the session and no turn
     * @param kind    what happened, named by the capability, e.g.
     *                {@code session.title}
     * @param details what belongs to the kind, in values that survive being
     *                sent: strings, numbers, booleans, lists and maps
     */
    record Notice(SessionId sessionId, TurnId turnId, String kind, Map<String, Object> details)
            implements Lifecycle {

        public Notice {
            details = Map.copyOf(details);
        }
    }
}
