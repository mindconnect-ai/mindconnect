package ai.mindconnect.agent.runtime.api.domain;

import java.util.Map;

/**
 * What the caller decides about a new session; the id and the time are the
 * runtime's. Only the user is needed — see {@link Session} for the rest.
 */
public record SessionSpec(
        UserId userId,
        SessionId parentId,
        AgentDefinition agent,
        Map<String, Object> metadata
) {

    public SessionSpec {
        metadata = Map.copyOf(metadata);
    }

    /** A root session with no agent and no metadata of its own. */
    public static SessionSpec of(UserId userId) {
        return new SessionSpec(userId, null, null, Map.of());
    }
}
