package ai.mindconnect.agentrest.dto;

import java.util.List;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.engine.service.UserSessionFeed;

import ai.mindconnect.agent.EntityId;

/**
 * One frame of the user stream ({@code GET /api/users/{userId}/stream}):
 * an event of the user's sessions on the wire, flat, with a {@code type}
 * discriminator and only the fields that event carries. {@code seq} is the cursor a client
 * hands back as {@code afterSeq} on reconnect.
 *
 * <table>
 *   <tr><th>type</th><th>fields</th></tr>
 *   <tr><td>{@code session_started}</td><td>{@code sessionId}, {@code agentDefinitionId}, {@code sessionType}</td></tr>
 *   <tr><td>{@code session_titled}</td><td>{@code sessionId}, {@code title}</td></tr>
 *   <tr><td>{@code turn_started}</td><td>{@code sessionId}, {@code turnId}</td></tr>
 *   <tr><td>{@code turn_finished}</td><td>{@code sessionId}, {@code turnId}, {@code outcome}</td></tr>
 *   <tr><td>{@code approval_requested}</td><td>{@code sessionId} (the root), {@code callId}, {@code toolName}</td></tr>
 * </table>
 */
public record UserEventFrame(long seq, String type, String sessionId, String turnId, String outcome,
                             String callId, String toolName, Boolean approved, String title,
                             String agentDefinitionId, String sessionType) {

    /** The first frame of every user stream: what the buffer still holds. */
    public record Attached(String type, long firstBufferedSeq, long latestSeq) {
        public static Attached of(long firstBufferedSeq, long latestSeq) {
            return new Attached("attached", firstBufferedSeq, latestSeq);
        }
    }

    /** The notice a session opened for a user is told with; its details name {@code agentId} and {@code type}. */
    public static final String SESSION_STARTED = "session.started";
    /** The notice of the title capability; its details name the {@code title}. */
    private static final String SESSION_TITLED = "session.title";

    /**
     * What an event of a user's sessions is on the wire: one frame, several
     * for a turn that asks about several tool calls, none for what this
     * stream does not tell — a sub-agent's turns among it.
     */
    public static List<UserEventFrame> of(UserSessionFeed.Entry entry) {
        long seq = entry.seq();
        String session = str(entry.event().sessionId());
        SessionEvent event = entry.event();
        return switch (event.known()) {
            case StreamEvent.AskingLlm asking when !entry.underAnotherTurn() && !asking.resumed() ->
                    List.of(new UserEventFrame(seq, "turn_started", session, event.turnId().value(),
                            null, null, null, null, null, null, null));
            case StreamEvent.Done done when !entry.underAnotherTurn() -> List.of(new UserEventFrame(seq,
                    "turn_finished", session, event.turnId().value(), outcome(done.status()),
                    null, null, null, null, null, null));
            case StreamEvent.Error error when !entry.underAnotherTurn() -> List.of(new UserEventFrame(seq,
                    "turn_finished", session, event.turnId().value(), outcome(error.status()),
                    null, null, null, null, null, null));
            case StreamEvent.ApprovalRequested asked when !entry.underAnotherTurn() -> List.of(new UserEventFrame(seq,
                    "approval_requested", session, null, null, asked.callId(), asked.toolName(),
                    null, null, null, null));
            case StreamEvent.Other other when SESSION_STARTED.equals(event.kind()) -> List.of(new UserEventFrame(seq,
                    "session_started", session, null, null, null, null, null, null,
                    text(event.details().get("agentId")), text(event.details().get("type"))));
            case StreamEvent.Other other when SESSION_TITLED.equals(event.kind()) -> List.of(new UserEventFrame(seq,
                    "session_titled", session, null, null, null, null, null,
                    text(event.details().get("title")), null, null));
            default -> List.of();
        };
    }

    private static String outcome(TurnStatus status) {
        return switch (status) {
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
            default -> "completed";
        };
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String str(EntityId id) {
        return id == null ? null : id.value();
    }
}
