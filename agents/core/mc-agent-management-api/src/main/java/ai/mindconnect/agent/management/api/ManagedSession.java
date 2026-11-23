package ai.mindconnect.agent.management.api;

import java.time.Instant;

import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;

/**
 * A session as a list of sessions shows it and an operator handles it. The
 * runtime's own {@code Session} is what a turn needs — whose it is, the
 * agent, metadata it does not read; this is the same session with what a
 * person looks at spelled out.
 *
 * <p>Title, type, status and directories are kept in the session's metadata
 * under the keys named here, so whoever opens a session through the runtime
 * can set them from the start.
 *
 * @param parentId  the session this one stands under; {@code null} at the top
 * @param agentName the name of the session's agent; {@code null} for a
 *                  session whose turns each bring their own
 * @param title     {@code null} until one was found or given
 * @param type      what kind of session it is, for a client that keeps
 *                  several kinds apart; {@link #CHAT} unless it says otherwise
 */
public record ManagedSession(
        SessionId id,
        UserId userId,
        SessionId parentId,
        String agentName,
        String title,
        String type,
        Status status,
        SessionDirectories directories,
        Instant createdAt
) {

    /** Metadata key of a session's title. */
    public static final String TITLE = "title";
    /** Metadata key of a session's type. */
    public static final String TYPE = "type";
    /** Metadata key of a session's status, the name of a {@link Status}. */
    public static final String STATUS = "status";
    /** Metadata key of the directory a session's tools work in. */
    public static final String WORKING_DIRECTORY = "workingDir";
    /** Metadata key of the further directories a session's tools may reach: a list of paths. */
    public static final String ADDITIONAL_DIRECTORIES = "additionalDirs";

    /** The type of a session that names none: a conversation with a person. */
    public static final String CHAT = "chat";

    /** Whether a session is still worked in. Not whether a turn runs in it right now: that its turns say. */
    public enum Status {
        ACTIVE,
        COMPLETED,
        ERROR,
        CANCELLED
    }
}
