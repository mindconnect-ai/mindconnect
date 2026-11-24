package ai.mindconnect.agent.runtime.api.domain;

import ai.mindconnect.agent.UserId;

import ai.mindconnect.agent.SessionId;

import java.util.Map;
import java.util.Objects;

/**
 * What the caller decides about a new session; the id and the time are the
 * runtime's. See {@link Session} for what each part is.
 *
 * @param agent the agent that answers in the session; a session without
 *              one could not run a turn, so there is none
 */
public record SessionSpec(
        UserId userId,
        SessionId parentId,
        AgentDefinition agent,
        Map<String, Object> metadata
) {

    public SessionSpec {
        Objects.requireNonNull(agent, "a session needs an agent");
        metadata = Map.copyOf(metadata);
    }
}
