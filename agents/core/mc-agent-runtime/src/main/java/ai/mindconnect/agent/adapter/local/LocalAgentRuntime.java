package ai.mindconnect.agent.adapter.local;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.in.AgentChatClient;
import ai.mindconnect.agent.port.in.AgentRuntime;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentRegistryService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.UUID;

/**
 * In-process {@link AgentRuntime} bound to one {@code (auth, namespace)}.
 *
 * <p>Sessions are created and looked up through {@link AgentSessionService};
 * agents through {@link AgentRegistryService}; chat turns through
 * {@link AgentChatService}. Cross-tenant access is blocked here in the
 * adapter: any session whose namespace or userId does not match the bound
 * identity is reported as {@code notFound}.
 */
public class LocalAgentRuntime implements AgentRuntime {

    private final AgentRegistryService registryService;
    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final AuthenticationInfo auth;
    private final Namespace namespace;

    public LocalAgentRuntime(AgentRegistryService registryService,
                              AgentSessionService sessionService,
                              AgentChatService chatService,
                              AuthenticationInfo auth,
                              Namespace namespace) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.auth = auth;
        this.namespace = namespace;
    }

    @Override
    public AgentChatClient openChat(UUID agentId) {
        AgentDefinition def = registryService.find(namespace, agentId)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", agentId.toString()));
        AgentSession session = sessionService.openChat(agentId, namespace, auth.userId());
        return new LocalAgentChatClient(chatService, sessionService, session, def);
    }

    @Override
    public AgentChatClient attachChat(UUID sessionId) {
        AgentSession session = loadSessionInTenant(sessionId);
        AgentDefinition def = registryService.find(namespace, session.agentDefinitionId())
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", session.agentDefinitionId().toString()));
        return new LocalAgentChatClient(chatService, sessionService, session, def);
    }

    @Override
    public List<AgentSession> listSessions(UUID agentId) {
        return sessionService.listSessions(agentId, namespace, auth.userId());
    }

    @Override
    public void deleteSession(UUID sessionId) {
        loadSessionInTenant(sessionId);   // tenant check; throws notFound otherwise
        sessionService.deleteSession(sessionId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Loads the session and verifies it belongs to the bound namespace and
     * userId. Cross-tenant probing surfaces as {@code notFound}.
     */
    private AgentSession loadSessionInTenant(UUID sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        if (!session.namespace().equals(namespace) || !session.userId().equals(auth.userId())) {
            throw DomainException.notFound("AgentSession", sessionId.toString());
        }
        return session;
    }
}
