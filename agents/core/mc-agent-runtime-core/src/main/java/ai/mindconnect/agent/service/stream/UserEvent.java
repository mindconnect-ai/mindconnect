package ai.mindconnect.agent.service.stream;

import java.util.UUID;

/**
 * What a user's clients learn about their sessions without being attached
 * to any one of them: a session was opened or got its title, a turn began
 * or ended, a tool waits for an answer. Coarse on purpose — the tokens and
 * tool calls of a turn stay on the session's own stream
 * ({@link SessionChannels}); this is the feed a session list, a header badge
 * or a notification reads, and what lets a second client find out that
 * something happened in a session it is not looking at.
 *
 * <p>Every event names its session. The user is not on the event because the
 * channel is the user's ({@link UserChannels}); an event never crosses users.
 */
public sealed interface UserEvent
        permits UserEvent.SessionStarted, UserEvent.SessionTitled,
                UserEvent.TurnStarted, UserEvent.TurnFinished,
                UserEvent.ApprovalRequested, UserEvent.ApprovalAnswered {

    /** The session this happened in — the root session for a bubbled approval. */
    UUID sessionId();

    /** How a turn ended, as far as the caller who submitted it can tell. */
    enum TurnOutcome { COMPLETED, FAILED, CANCELLED }

    /** A top-level session was opened. Sub-agent sessions do not announce themselves. */
    record SessionStarted(UUID sessionId, UUID agentDefinitionId) implements UserEvent {}

    /** The session got its generated title after the first exchange. */
    record SessionTitled(UUID sessionId, String title) implements UserEvent {}

    /** A turn was submitted; the session's own stream carries what it does. */
    record TurnStarted(UUID sessionId, UUID turnId) implements UserEvent {}

    /** The turn's task reached a terminal state, or the wait for it ended. */
    record TurnFinished(UUID sessionId, UUID turnId, TurnOutcome outcome) implements UserEvent {}

    /**
     * A tool waits at the approval gate. {@code sessionId} is the ROOT
     * session — the one whose chat shows the card, even when a sub-agent
     * asked — so a client can navigate straight to where the answer goes.
     */
    record ApprovalRequested(UUID sessionId, String callId, String toolName) implements UserEvent {}

    /** The question was answered (from any client) and the tool's task was told. */
    record ApprovalAnswered(UUID sessionId, String callId, boolean approved) implements UserEvent {}
}
