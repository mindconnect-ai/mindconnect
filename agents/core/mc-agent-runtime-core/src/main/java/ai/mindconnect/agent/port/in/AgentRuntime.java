package ai.mindconnect.agent.port.in;

import ai.mindconnect.agent.domain.AgentSession;

import java.util.List;
import java.util.UUID;

/**
 * Runtime use cases: opening, attaching and listing chat sessions.
 *
 * <p>Instances are bound to a {@code (AuthenticationInfo, Namespace)} pair at
 * construction. Methods therefore take no auth or tenant arguments — the
 * binding is implicit.
 *
 * <p>Each {@link AgentChatClient} returned is a stateful handle for one
 * session. Multiple clients may reference the same session id; the underlying
 * service guarantees only one concurrent {@code send()} per session.
 */
public interface AgentRuntime {

    /**
     * Opens a brand-new chat session for the given agent and returns a client
     * bound to it. Throws if the agent does not exist in the bound namespace.
     */
    AgentChatClient openChat(UUID agentId);

    /**
     * Attaches to an existing session and returns a client bound to it.
     * Throws if the session does not exist or is not visible in the bound
     * (namespace, user) context.
     */
    AgentChatClient attachChat(UUID sessionId);

    /** Lists existing sessions for the given agent in the bound (namespace, user) context. */
    List<AgentSession> listSessions(UUID agentId);

    /**
     * Permanently deletes a session and all its associated data (working
     * memory snapshot, conversation summaries). The conversation messages
     * themselves are owned by the conversation, not the session, and are
     * not deleted by this call.
     */
    void deleteSession(UUID sessionId);
}
