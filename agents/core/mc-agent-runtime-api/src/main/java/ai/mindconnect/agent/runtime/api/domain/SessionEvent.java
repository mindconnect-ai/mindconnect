package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * What happens in a session, coarse enough to follow many sessions at once:
 * turns begin and end, messages and tool calls begin and end. Whoever shows
 * a session as it happens reads {@link TurnReported} as well: what the turn
 * itself reports, fragments of an answer being written included.
 *
 * <p>Every event names its session and, where it belongs to one, its turn, so a listener that hears
 * several sessions — all of a user's, or a session with the sessions under
 * it — can tell them apart.
 */
public sealed interface SessionEvent {

    SessionId sessionId();

    TurnId turnId();

    /**
     * What a turn of the session reports, as the agent loop says it: the
     * fragments of the answer and of the reasoning, every message as it is
     * written, the outcome. The other events are the digest of these; a
     * listener that shows a list of sessions passes this one by.
     */
    record TurnReported(SessionId sessionId, TurnId turnId, TurnEvent event) implements SessionEvent { }

    /** A turn began to run. */
    record TurnStarted(SessionId sessionId, TurnId turnId, String agentName) implements SessionEvent { }

    /**
     * A turn stopped because it has to ask, and has come back: nothing more
     * happens in it until it is resumed with answers.
     *
     * @param interruptions what this turn asks
     */
    record TurnInterrupted(SessionId sessionId, TurnId turnId, List<Interruption> interruptions)
            implements SessionEvent {

        public TurnInterrupted {
            interruptions = List.copyOf(interruptions);
        }
    }

    /** A turn is over — completed, incomplete, cancelled or failed. */
    record TurnFinished(SessionId sessionId, TurnId turnId, TurnStatus status) implements SessionEvent { }

    /** The agent has begun to answer. The answer follows as a {@link MessageFinished}. */
    record MessageStarted(SessionId sessionId, TurnId turnId) implements SessionEvent { }

    /** A message is in the conversation — what the user said, or what the agent answered. */
    record MessageFinished(SessionId sessionId, TurnId turnId, TurnMessage message) implements SessionEvent { }

    /** A tool call the model asked for was taken up. */
    record ToolCallStarted(SessionId sessionId, TurnId turnId, String callId, String toolName)
            implements SessionEvent { }

    /** A tool call has its result; {@code failed} says whether the model was told it went wrong. */
    record ToolCallFinished(SessionId sessionId, TurnId turnId, String callId, String toolName, boolean failed)
            implements SessionEvent { }

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
            implements SessionEvent {

        public Notice {
            details = Map.copyOf(details);
        }
    }
}
