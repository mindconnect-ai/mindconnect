package ai.mindconnect.agent.management.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * @param agent     the agent the session runs on; {@code null} for a
 *                  session whose turns each bring their own
 * @param title     {@code null} until one was found or given
 * @param type      what kind of session it is, for a client that keeps
 *                  several kinds apart; {@link #CHAT} unless it says otherwise
 * @param metadata  everything the session's metadata holds, the entries
 *                  spelled out above included: where a user interface keeps
 *                  what it alone knows about a session, under keys of its own
 */
public record ManagedSession(
        SessionId id,
        UserId userId,
        SessionId parentId,
        SessionAgent agent,
        String title,
        String type,
        Status status,
        SessionDirectories directories,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public ManagedSession {
        metadata = Map.copyOf(metadata);
    }

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

    /**
     * Metadata key of the stored agent a session was opened with: its
     * {@link AgentId}. Absent for a session whose agent is kept nowhere
     * else — one made for this session alone.
     */
    public static final String AGENT_ID = "agentId";

    /** Metadata of a session: {@link #REF} for one that refers to the managed agent under {@link #AGENT_ID}. */
    public static final String AGENT = "agent";
    public static final String REF = "ref";
    public static final String OWN = "own";

    /** The type of a session that names none: a conversation with a person. */
    public static final String CHAT = "chat";

    /** The stored agent the session was opened with, where it was opened with one. */
    /**
     * Metadata key of the files attached to a session: a list of entries
     * with the fields of a {@link SessionAttachment}. What tells a model
     * about them reads the same entry.
     */
    public static final String ATTACHMENTS = "attachedFiles";

    /** The files attached to the session, in the order they were attached. */
    public List<SessionAttachment> attachments() {
        if (!(metadata.get(ATTACHMENTS) instanceof List<?> stored)) {
            return List.of();
        }
        return stored.stream()
                .filter(Map.class::isInstance)
                .map(entry -> (Map<?, ?>) entry)
                .map(entry -> new SessionAttachment(
                        entry.get("id") instanceof String id ? id : null,
                        String.valueOf(entry.get("name")),
                        entry.get("mediaType") instanceof String mediaType ? mediaType : null,
                        entry.get("sizeBytes") instanceof Number size ? size.longValue() : 0,
                        entry.get("path") instanceof String path ? path : null))
                .toList();
    }

    public Optional<AgentId> agentId() {
        return metadata.get(AGENT_ID) instanceof String id ? Optional.of(AgentId.of(id)) : Optional.empty();
    }

    /** Whether a session is still worked in. Not whether a turn runs in it right now: that its turns say. */
    public enum Status {
        ACTIVE,
        COMPLETED,
        ERROR,
        CANCELLED
    }
}
