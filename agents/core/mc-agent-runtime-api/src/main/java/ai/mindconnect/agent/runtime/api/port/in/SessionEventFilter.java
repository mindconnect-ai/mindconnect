package ai.mindconnect.agent.runtime.api.port.in;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;

/**
 * Which sessions a listener follows, and how closely. Following many
 * sessions and following every fragment of an answer are two wishes: a list
 * of sessions needs to know which of them are busy, and would be told
 * thousands of events per answer it never draws. So a listener hears the
 * turns begin, ask and end, and asks for the stream where it shows one.
 *
 * @param sessionId     the one session followed; {@code null} for the
 *                      sessions of a user or all sessions
 * @param userId        the user whose sessions are followed; {@code null}
 *                      for one session or all sessions
 * @param childSessions with {@code sessionId}: also every session under it,
 *                      however deep — the sessions of its sub-agents and its
 *                      forks as children
 * @param detail        how much of what happens in those sessions is told
 */
public record SessionEventFilter(SessionId sessionId, UserId userId, boolean childSessions, Detail detail) {

    /** How much of a session a listener is told. */
    public enum Detail {
        /**
         * Turns beginning, asking and ending, and what capabilities say
         * about the session: {@link SessionEvent.TurnStarted},
         * {@link SessionEvent.TurnInterrupted}, {@link SessionEvent.TurnFinished},
         * {@link SessionEvent.Notice}. Enough to show which session is busy
         * and which waits for an answer.
         */
        LIFECYCLE,
        /** Everything: messages, tool calls and what each turn reports as it runs, fragments included. */
        STREAM
    }

    /** One session, its turns beginning, asking and ending. */
    public static SessionEventFilter session(SessionId sessionId) {
        return new SessionEventFilter(sessionId, null, false, Detail.LIFECYCLE);
    }

    /**
     * Every session of this user — the ones open now and the ones opened
     * later, sub-agent sessions included.
     */
    public static SessionEventFilter user(UserId userId) {
        return new SessionEventFilter(null, userId, false, Detail.LIFECYCLE);
    }

    /** Every session of the namespace the listener subscribes in, whoever its user is. */
    public static SessionEventFilter all() {
        return new SessionEventFilter(null, null, false, Detail.LIFECYCLE);
    }

    /** As this one, and every session under the session it names. */
    public SessionEventFilter withChildSessions() {
        return new SessionEventFilter(sessionId, userId, true, detail);
    }

    /** As this one, told everything that happens: for a listener that shows the session as it happens. */
    public SessionEventFilter withStream() {
        return new SessionEventFilter(sessionId, userId, childSessions, Detail.STREAM);
    }

    /** Whether a listener with this detail is told the event; which session it is from is not looked at. */
    public boolean tells(SessionEvent event) {
        return detail == Detail.STREAM || switch (event) {
            case SessionEvent.TurnStarted started -> true;
            case SessionEvent.TurnInterrupted interrupted -> true;
            case SessionEvent.TurnFinished finished -> true;
            case SessionEvent.Notice notice -> true;
            default -> false;
        };
    }

    /** Whether the filter follows this session directly — not as a session under the one it names. */
    public boolean names(Session session) {
        if (sessionId != null) {
            return sessionId.equals(session.id());
        }
        return userId == null || userId.equals(session.userId());
    }
}
