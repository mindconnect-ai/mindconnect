package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.stream.UserEvent;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.port.in.ConversationManager;

/**
 * Opens a chat through the session management and gives the earlier side of
 * the application what it expects of one: a conversation record under the
 * session's id — whoever still reads a history the earlier way asks for it —
 * and the news on the user's stream.
 */
public final class ChatOpener {

    private final SessionManagement sessions;
    private final AgentSessionRepository chatSessions;
    private final ConversationManager conversations;
    private final UserChannels users;
    private final EarlierAgentConverter converter;

    ChatOpener(SessionManagement sessions, AgentSessionRepository chatSessions, ConversationManager conversations,
               UserChannels users, EarlierAgentConverter converter) {
        this.converter = converter;
        this.sessions = sessions;
        this.chatSessions = chatSessions;
        this.conversations = conversations;
        this.users = users;
    }

    /**
     * Opens a chat on a stored agent, which it follows from then on.
     *
     * @param type             what kind of session it is; {@code null} for a chat
     * @param workingDirectory {@code null} for the directory the installation gives the chat
     * @throws IllegalArgumentException for an agent that does not exist or a directory the user may not have
     */
    public AgentSession open(AgentId agent, UserId user, String type, String workingDirectory,
                             List<String> additionalDirectories) {
        AgentSession chat = opened(sessions.open(ai.mindconnect.agent.runtime.api.domain.UserId.of(user.value()), null,
                new SessionAgent.Ref(ai.mindconnect.agent.management.api.AgentId.of(agent.value())), type,
                new SessionDirectories(workingDirectory,
                        additionalDirectories == null ? List.of() : additionalDirectories)));
        users.publish(user, new UserEvent.SessionStarted(chat.id(), chat.agentDefinitionId(), chat.type()));
        return chat;
    }

    /** Opens a chat on a definition of its own, which no stored agent stands behind. */
    public AgentSession open(AgentDefinition definition, UserId user) {
        AgentSession chat = opened(sessions.open(ai.mindconnect.agent.runtime.api.domain.UserId.of(user.value()), null,
                new SessionAgent.Own(converter.runs(definition), null), null, SessionDirectories.NONE));
        users.publish(user, new UserEvent.SessionStarted(chat.id(), chat.agentDefinitionId(), chat.type()));
        return chat;
    }

    /**
     * Opens a chat on a stored agent under another chat, in that chat's
     * directories — a sub-agent tried out from the chat that may call it.
     * It is the chat's business and no news for the user's list.
     */
    public AgentSession openUnder(AgentSession parent, AgentId agent) {
        return opened(sessions.open(ai.mindconnect.agent.runtime.api.domain.UserId.of(parent.userId().value()),
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(parent.id().value()),
                new SessionAgent.Ref(ai.mindconnect.agent.management.api.AgentId.of(agent.value())), null,
                new SessionDirectories(parent.workingDir(), parent.additionalDirs())));
    }

    /** The session as the earlier side reads it, with the conversation record that side asks for. */
    private AgentSession opened(ManagedSession session) {
        AgentSession chat = chatSessions.findById(SessionId.of(session.id().value())).orElseThrow();
        ConversationId conversation = chat.conversationId();
        conversations.createConversation(conversation, "Chat", ConversationType.USER_AGENT, List.of(
                Participant.user(conversation, chat.userId(), chat.userId().value()),
                Participant.agent(conversation, chat.agentDefinitionId(), "agent")));
        return chat;
    }
}
