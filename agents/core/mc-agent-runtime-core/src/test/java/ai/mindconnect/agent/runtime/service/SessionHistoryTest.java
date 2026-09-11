package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a session's history hands back is the whole conversation. It used to
 * be the first 200 messages, so a long chat kept showing its opening and
 * dropped everything after it — the message just sent included, while the
 * runtime itself went on reading all of them.
 */
class SessionHistoryTest {

    private final ConversationId conversationId = ConversationId.random();

    @Test
    void aConversationPastTheOldCapComesBackEntire() {
        Conversations conversations = new Conversations(conversationId);
        for (int i = 1; i <= 315; i++) {
            conversations.append("message " + i);
        }
        AgentSession session = AgentSession.start(AgentId.random(), UserId.of("mc_user"), conversationId);
        AgentSessionService service = serviceFor(session, conversations);

        List<Message> history = service.loadHistory(session.id());

        assertThat(history).hasSize(315);
        assertThat(history.get(history.size() - 1).content()).isEqualTo("message 315");
        assertThat(conversations.requestedFor).isEqualTo(conversationId);
        assertThat(conversations.requested.page()).isZero();
        assertThat(conversations.requested.size()).as("one page, big enough for any chat")
                .isGreaterThanOrEqualTo(315);
    }

    private AgentSessionService serviceFor(AgentSession session, ConversationManager conversations) {
        AgentSessionRepository sessions = new AgentSessionRepository() {
            @Override public AgentSession save(AgentSession s) { return s; }
            @Override public Optional<AgentSession> findById(SessionId id) {
                return session.id().equals(id) ? Optional.of(session) : Optional.empty();
            }
            @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) {
                return List.of();
            }
            @Override public List<AgentSession> findByUser(UserId u) { return List.of(); }
            @Override public List<AgentSession> findByParentSession(SessionId parent) { return List.of(); }
            @Override public void deleteById(SessionId id) { }
        };
        return new AgentSessionService(null, sessions, conversations, null, null, null, null, null);
    }

    /** Only what {@code loadHistory} touches; the page it was asked for is kept. */
    private static final class Conversations implements ConversationManager {
        private final ConversationId conversationId;
        private final List<Message> messages = new ArrayList<>();
        private PageRequest requested;
        private ConversationId requestedFor;
        private int seq;

        private Conversations(ConversationId conversationId) {
            this.conversationId = conversationId;
        }

        void append(String content) {
            messages.add(Message.of(conversationId, UUID.randomUUID().toString(), ParticipantType.USER,
                    MessageType.CHAT, content, ++seq));
        }

        @Override public List<Message> loadHistory(ConversationId id, PageRequest page) {
            this.requestedFor = id;
            this.requested = page;
            return messages.stream().skip(page.offset()).limit(page.size()).toList();
        }

        @Override public ConversationHistory loadCompleteHistory(ConversationId id) {
            return ConversationHistory.of(id, List.copyOf(messages));
        }

        @Override public Conversation createConversation(ConversationId id, String title,
                ConversationType type, List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Conversation> findById(ConversationId id) { return Optional.empty(); }
        @Override public List<Conversation> list(PageRequest page) {
            return List.of();
        }
        @Override public Message addMessageToConversation(ConversationId id, String senderId,
                ParticipantType senderType, MessageType type, String content, ChatTurnId turnId) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId id, String senderId,
                ParticipantType senderType, MessageType type, String content, ChatTurnId turnId,
                Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId id, String senderId,
                ParticipantType senderType, MessageType type,
                List<ai.mindconnect.message.domain.ContentPart> parts, ChatTurnId turnId,
                Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public void compressMessage(ConversationId id, MessageId messageId, String stub, Integer tokens) { }
        @Override public void updateTokenCount(ConversationId id, MessageId messageId, int tokenCount) { }
        @Override public void updateDurationMs(ConversationId id, MessageId messageId, long durationMs) { }
        @Override public int deleteMessages(ConversationId id, int fromSeq, int toSeq) { return 0; }
    }
}
