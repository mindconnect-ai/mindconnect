package ai.mindconnect.agent.runtime.api.domain;

import java.time.Instant;
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
}
