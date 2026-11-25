package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.management.api.SessionLifecycleEvent;

/**
 * One frame of the user stream ({@code GET /api/users/{userId}/stream}):
 * what changed about one of the user's sessions, flat, with a {@code type}
 * discriminator and only the fields that change carries. Whoever wants more
 * reads the session.
 *
 * <table>
 *   <tr><th>type</th><th>fields</th></tr>
 *   <tr><td>{@code session_started}</td><td>{@code sessionId}, {@code agentDefinitionId}, {@code sessionType}</td></tr>
 *   <tr><td>{@code session_titled}</td><td>{@code sessionId}, {@code title}</td></tr>
 *   <tr><td>{@code session_status}</td><td>{@code sessionId}, {@code status}: {@code IDLE}, {@code BUSY} or
 *       {@code WAITING}</td></tr>
 *   <tr><td>{@code session_deleted}</td><td>{@code sessionId}</td></tr>
 * </table>
 */
public record UserEventFrame(String type, String sessionId, String status, String title,
                             String agentDefinitionId, String sessionType) {

    /** The first frame of every user stream: the stream is attached, and tells what changes from now on. */
    public record Attached(String type) {
        public static Attached now() {
            return new Attached("attached");
        }
    }

    public static UserEventFrame of(SessionLifecycleEvent event) {
        String session = event.sessionId().value();
        return switch (event) {
            case SessionLifecycleEvent.Opened opened -> new UserEventFrame("session_started", session, null, null,
                    opened.agentId() == null ? null : opened.agentId().value(), opened.type());
            case SessionLifecycleEvent.Renamed renamed ->
                    new UserEventFrame("session_titled", session, null, renamed.title(), null, null);
            case SessionLifecycleEvent.StatusChanged changed ->
                    new UserEventFrame("session_status", session, changed.status().name(), null, null, null);
            case SessionLifecycleEvent.Deleted deleted ->
                    new UserEventFrame("session_deleted", session, null, null, null, null);
        };
    }
}
