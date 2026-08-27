package ai.mindconnect.agent.protocol.api;

import ai.mindconnect.agent.protocol.Session;

import java.util.Optional;

/**
 * Opens and resolves sessions — the third and last surface of the protocol,
 * completing the triangle:
 *
 * <pre>
 * Sessions        open(agent)  → binds a (new or existing) conversation to an agent
 * AgentResponses  create(...)  → runs one response on a session
 * Conversations   items(...)   → reads the durable log behind it
 * </pre>
 *
 * Agents themselves are referenced by name only. Their definitions (prompt,
 * model, tool set, memory strategy) are managed through the admin domain —
 * deliberately not through this protocol.
 */
public interface Sessions {

    /** Opens a session with a fresh conversation, bound to the named agent. */
    Session open(String namespace, String agentName);

    /**
     * Binds the named agent to an EXISTING conversation — continuing the same
     * history with a different agent, or re-opening a closed session's log.
     */
    Session openOn(String conversationId, String agentName);

    Optional<Session> get(String sessionId);
}
