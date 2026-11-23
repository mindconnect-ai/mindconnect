package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;

/**
 * The runtime's session store, with one thing carried over to where the
 * chat page keeps its sessions: the title. The runtime finds a title after
 * the first exchange and keeps it in its own session; the page lists chats
 * by the title of its own. A chat the user already named keeps its name.
 */
final class ChatSessionStore implements SessionStore {

    private final SessionStore sessions;
    private final AgentSessionRepository chatSessions;

    ChatSessionStore(SessionStore sessions, AgentSessionRepository chatSessions) {
        this.sessions = sessions;
        this.chatSessions = chatSessions;
    }

    @Override
    public Session update(SessionId sessionId, UnaryOperator<Session> change) {
        Session changed = sessions.update(sessionId, change);
        if (changed.metadata().get(SessionTitlePlugin.TITLE) instanceof String title) {
            chatSessions.update(ai.mindconnect.agent.SessionId.of(sessionId.value()),
                    chat -> chat.title() == null || chat.title().isBlank() ? chat.withTitle(title) : chat);
        }
        return changed;
    }

    @Override
    public void create(Session session) {
        sessions.create(session);
    }

    @Override
    public Optional<Session> find(SessionId sessionId) {
        return sessions.find(sessionId);
    }

    @Override
    public List<TurnMessage> messages(SessionId sessionId) {
        return sessions.messages(sessionId);
    }

    @Override
    public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> messages) {
        return sessions.append(sessionId, messages);
    }

    @Override
    public void updateMessageMetadata(SessionId sessionId, String messageId,
                                      UnaryOperator<Map<String, Object>> change) {
        sessions.updateMessageMetadata(sessionId, messageId, change);
    }
}
