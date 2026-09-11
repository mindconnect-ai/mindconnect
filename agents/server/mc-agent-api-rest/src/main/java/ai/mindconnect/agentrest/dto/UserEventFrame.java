package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.runtime.service.stream.UserEvent;

import ai.mindconnect.agent.EntityId;

/**
 * One frame of the user stream ({@code GET /api/users/{userId}/stream}): a
 * {@link UserEvent} on the wire, flat, with a {@code type} discriminator and
 * only the fields that event carries. {@code seq} is the cursor a client
 * hands back as {@code afterSeq} on reconnect.
 *
 * <table>
 *   <tr><th>type</th><th>fields</th></tr>
 *   <tr><td>{@code session_started}</td><td>{@code sessionId}, {@code agentDefinitionId}</td></tr>
 *   <tr><td>{@code session_titled}</td><td>{@code sessionId}, {@code title}</td></tr>
 *   <tr><td>{@code turn_started}</td><td>{@code sessionId}, {@code turnId}</td></tr>
 *   <tr><td>{@code turn_finished}</td><td>{@code sessionId}, {@code turnId}, {@code outcome}</td></tr>
 *   <tr><td>{@code approval_requested}</td><td>{@code sessionId} (the root), {@code callId}, {@code toolName}</td></tr>
 *   <tr><td>{@code approval_answered}</td><td>{@code sessionId}, {@code callId}, {@code approved}</td></tr>
 * </table>
 */
public record UserEventFrame(long seq, String type, String sessionId, String turnId, String outcome,
                             String callId, String toolName, Boolean approved, String title,
                             String agentDefinitionId) {

    /** The first frame of every user stream: what the buffer still holds. */
    public record Attached(String type, long firstBufferedSeq, long latestSeq) {
        public static Attached of(long firstBufferedSeq, long latestSeq) {
            return new Attached("attached", firstBufferedSeq, latestSeq);
        }
    }

    public static UserEventFrame of(long seq, UserEvent event) {
        String session = str(event.sessionId());
        return switch (event) {
            case UserEvent.SessionStarted e -> new UserEventFrame(seq, "session_started", session,
                    null, null, null, null, null, null, str(e.agentDefinitionId()));
            case UserEvent.SessionTitled e -> new UserEventFrame(seq, "session_titled", session,
                    null, null, null, null, null, e.title(), null);
            case UserEvent.TurnStarted e -> new UserEventFrame(seq, "turn_started", session,
                    str(e.turnId()), null, null, null, null, null, null);
            case UserEvent.TurnFinished e -> new UserEventFrame(seq, "turn_finished", session,
                    str(e.turnId()), e.outcome().name().toLowerCase(java.util.Locale.ROOT),
                    null, null, null, null, null);
            case UserEvent.ApprovalRequested e -> new UserEventFrame(seq, "approval_requested", session,
                    null, null, e.callId(), e.toolName(), null, null, null);
            case UserEvent.ApprovalAnswered e -> new UserEventFrame(seq, "approval_answered", session,
                    null, null, e.callId(), null, e.approved(), null, null);
        };
    }

    private static String str(EntityId id) {
        return id == null ? null : id.value();
    }
}
