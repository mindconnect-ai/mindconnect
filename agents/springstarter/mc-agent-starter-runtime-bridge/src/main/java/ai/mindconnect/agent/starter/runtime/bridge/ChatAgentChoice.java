package ai.mindconnect.agent.starter.runtime.bridge;

import java.time.Instant;

import ai.mindconnect.agent.management.api.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.common.DomainException;

/**
 * The agent a chat runs on, for a page that still draws agents in the
 * earlier runtime's types: read from the session management, where the
 * choice is kept, and written there as a definition of the chat's own.
 */
public final class ChatAgentChoice {

    private final SessionManagement sessions;
    private final EarlierAgentConverter converter;

    ChatAgentChoice(SessionManagement sessions, EarlierAgentConverter converter) {
        this.sessions = sessions;
        this.converter = converter;
    }

    /** The agent the chat's next turn runs on. */
    public AgentDefinition effective(AgentSession chat) {
        ai.mindconnect.agent.runtime.api.domain.AgentDefinition runs = sessions.agentOf(runtimeId(chat))
                .orElseThrow(() -> DomainException.notFound("AgentDefinition", chat.agentDefinitionId().toString()));
        AgentDefinition written = converter.written(runs);
        Object id = runs.metadata().get(AgentDefinitionMapper.AGENT_ID);
        Instant now = Instant.now();
        return converter.earlier(new ManagedAgent(
                AgentId.of(id instanceof String stored ? stored : chat.agentDefinitionId().value()), runs,
                written == null ? null : written.group(), written == null ? null : written.icon(),
                written == null ? null : written.welcomeMessage(), ManagedAgent.Status.ACTIVE, now, now, 1));
    }

    /** The chat runs on this definition from now on, and on no stored agent's. */
    public void own(AgentSession chat, AgentDefinition changed) {
        sessions.changeAgent(runtimeId(chat), converter.runs(changed));
    }

    private static ai.mindconnect.agent.runtime.api.domain.SessionId runtimeId(AgentSession chat) {
        return ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value());
    }
}
