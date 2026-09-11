package ai.mindconnect.agent.runtime.port.in;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.SessionId;

import java.util.List;

/**
 * Runtime use cases: opening, attaching and listing chat sessions.
 *
 * <p>Instances are bound to an {@code AuthenticationInfo} at construction,
 * and with it to one tenant and user. Methods therefore take no auth or
 * tenant arguments. Agents and sessions are addressed by their typed ids;
 * an id from another tenant than the bound one is rejected, as in
 * {@link AgentRegistry}.
 *
 * <p>Each {@link AgentChatClient} returned is a stateful handle for one
 * session. Multiple clients may reference the same session; the underlying
 * service guarantees only one concurrent {@code send()} per session.
 */
public interface AgentRuntime {

    /**
     * Opens a brand-new chat session for the agent and returns a client bound
     * to it. Throws if the agent does not exist.
     */
    AgentChatClient openChat(AgentId agentId);

    /**
     * Attaches to an existing session and returns a client bound to it.
     * Throws if the session does not exist or does not belong to the bound
     * user.
     */
    AgentChatClient attachChat(SessionId sessionId);

    /** The bound user's sessions with the agent. */
    List<AgentSession> listSessions(AgentId agentId);

    /**
     * Permanently deletes a session and all its associated data (working
     * memory snapshot, conversation summaries). The conversation messages
     * themselves are owned by the conversation, not the session, and are
     * not deleted by this call.
     */
    void deleteSession(SessionId sessionId);
}
