package ai.mindconnect.agent.runtime.api.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A conversation that can be continued. Data only: there is no handle to
 * hold, every call on the runtime names the session by its id.
 *
 * @param parentId      the session this one was opened under — a sub-agent's
 *                      session under its caller's; {@code null} at the root
 * @param agent         the agent of turns that bring none, whole and as it
 *                      was when the session got it — a definition changed
 *                      since does not reach the session by itself;
 *                      {@code null} when every turn brings its own. Its
 *                      model settings are the session's: there is no other
 *                      place for them
 * @param metadata      what an implementation or a plugin needs to know
 *                      about the session and the runtime does not — a
 *                      harness keeps the working directory here, the title
 *                      capability the title. Not the namespace: the same
 *                      session id may exist in several
 */
public record Session(
        SessionId id,
        UserId userId,
        SessionId parentId,
        AgentDefinition agent,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public Session {
        metadata = Map.copyOf(metadata);
    }

    /** This session without that entry in its metadata; the same session when it has none. */
    public Session withoutMetadata(String key) {
        Map<String, Object> changed = new HashMap<>(metadata);
        changed.remove(key);
        return new Session(id, userId, parentId, agent, changed, createdAt);
    }

    /** This session with another agent for the turns that bring none. */
    public Session withAgent(AgentDefinition agent) {
        return new Session(id, userId, parentId, agent, metadata, createdAt);
    }

    /** This session with one more entry in its metadata, or another value for one it has. */
    public Session withMetadata(String key, Object value) {
        Map<String, Object> changed = new HashMap<>(metadata);
        changed.put(key, value);
        return new Session(id, userId, parentId, agent, changed, createdAt);
    }
}
