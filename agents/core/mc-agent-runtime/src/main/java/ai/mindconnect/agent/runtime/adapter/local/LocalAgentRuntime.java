package ai.mindconnect.agent.runtime.adapter.local;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.in.AgentChatClient;
import ai.mindconnect.agent.runtime.port.in.AgentRuntime;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.common.DomainException;

import java.util.List;

/**
 * In-process {@link AgentRuntime} bound to one {@code auth}.
 *
 * <p>Sessions are created and looked up through {@link AgentSessionService};
 * agents through {@link AgentRegistryService}; chat turns through
 * {@link AgentChatService}. Another user's sessions are blocked here in the
 * adapter: any session whose userId does not match the bound
 * identity is reported as {@code notFound}.
 */
public class LocalAgentRuntime implements AgentRuntime {

    private final AgentRegistryService registryService;
    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final AuthenticationInfo auth;

    public LocalAgentRuntime(AgentRegistryService registryService,
                              AgentSessionService sessionService,
                              AgentChatService chatService,
                              AuthenticationInfo auth) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.auth = auth;
    }

    @Override
    public AgentChatClient openChat(AgentId agentId) {
        AgentDefinition def = registryService.find(agentId)
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", agentId.toString()));
        AgentSession session = sessionService.openChat(agentId, auth.userId());
        return new LocalAgentChatClient(chatService, sessionService, session, def);
    }

    @Override
    public AgentChatClient attachChat(SessionId sessionId) {
        AgentSession session = loadSessionOfUser(sessionId);
        AgentDefinition def = registryService.find(session.agentDefinitionId())
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", session.agentDefinitionId().toString()));
        return new LocalAgentChatClient(chatService, sessionService, session, def);
    }

    @Override
    public List<AgentSession> listSessions(AgentId agentId) {
        return sessionService.listSessions(agentId, auth.userId());
    }

    @Override
    public void deleteSession(SessionId sessionId) {
        loadSessionOfUser(sessionId);   // ownership check; throws notFound otherwise
        sessionService.deleteSession(sessionId);
    }

    private AgentSession loadSessionOfUser(SessionId sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        if (!session.userId().equals(auth.userId())) {
            throw DomainException.notFound("AgentSession", sessionId.toString());
        }
        return session;
    }
}
