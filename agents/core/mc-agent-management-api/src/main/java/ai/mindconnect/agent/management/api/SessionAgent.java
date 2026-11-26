package ai.mindconnect.agent.management.api;

import ai.mindconnect.agent.AgentId;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;

/**
 * The agent a session runs on: a managed agent it refers to, or a
 * definition of its own.
 */
public sealed interface SessionAgent {

    /**
     * A managed agent, as it stands when a turn begins: what is changed on
     * that agent reaches the session.
     */
    record Ref(AgentId id) implements SessionAgent { }

    /**
     * A definition kept in the session and changed only there.
     *
     * @param origin the managed agent it was taken from; {@code null} for
     *               one that never was a managed agent's
     */
    record Own(AgentDefinition definition, AgentId origin) implements SessionAgent { }
}
