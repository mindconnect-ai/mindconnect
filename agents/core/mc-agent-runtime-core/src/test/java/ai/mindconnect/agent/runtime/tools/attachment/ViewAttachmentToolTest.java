package ai.mindconnect.agent.runtime.tools.attachment;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ContentPart;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ViewAttachmentToolTest {

    private static final ConversationId CONVERSATION = ConversationId.random();
    private static final SessionId SESSION = SessionId.random();
    private static final AgentId AGENT = AgentId.random();
    private static final ChatTurnId TURN = ChatTurnId.random();

    private static final AttachedFile PHOTO = new AttachedFile("f-1", "photo.png", "image/png", 240);
    private static final AttachedFile NOTES = new AttachedFile("f-3", "notes.md", "text/markdown", 12);

    /** The conversation so far: the user's question and the assistant's tool call, in one turn. */
    private final List<Message> messages = new ArrayList<>(List.of(
            Message.of(CONVERSATION, UUID.randomUUID().toString(), ParticipantType.USER, MessageType.CHAT, "show me", 1)
                    .withTurnId(TURN),
            Message.of(CONVERSATION, AGENT.value(), ParticipantType.AGENT, MessageType.TOOL_CALL, "{}", 2)
                    .withTurnId(TURN).withRun(1)));

    private final Conversations conversations = new Conversations();
    private final Sessions sessions = new Sessions();
    private final ViewAttachmentTool tool = new ViewAttachmentTool(sessions, conversations, SESSION);

    private void session(AttachedFile... files) {
        sessions.session = new AgentSession(SESSION, AGENT, UserId.of("u"), CONVERSATION, "t",
                SessionStatus.ACTIVE, Instant.now(), null, null, null, null, List.of(), List.of(files));
    }

    @Test
    void showsTheImageAsAUserMessageOfTheCurrentTurn() {
        session(PHOTO, NOTES);

        String result = tool.execute(Map.of("name", "photo.png"));

        assertThat(result).contains("Showing 'photo.png'");
        Message inserted = conversations.appended.get(0);
        assertThat(inserted.senderType()).isEqualTo(ParticipantType.USER);
        assertThat(inserted.type()).isEqualTo(MessageType.CHAT);
        assertThat(inserted.turnId()).isEqualTo(TURN);
        assertThat(inserted.run()).isEqualTo(1);
        assertThat(inserted.parts()).containsExactly(
                new ContentPart.Text("[photo.png — image shown again at the assistant's request]"),
                new ContentPart.Image("f-1", "photo.png", "image/png", 240));
        assertThat(inserted.metadata()).containsEntry(ViewAttachmentTool.INSERTED_BY, ViewAttachmentTool.NAME)
                .containsEntry(ViewAttachmentTool.ATTACHMENT, "photo.png");
        assertThat(ViewAttachmentTool.insertedBy(inserted)).isTrue();
        assertThat(ViewAttachmentTool.insertedBy(messages.get(0))).isFalse();
    }

    @Test
    void theInsertedMessageStaysInTheTurnItWasInsertedInto() {
        session(PHOTO);
        tool.execute(Map.of("name", "photo.png"));

        ConversationHistory history = ConversationHistory.of(CONVERSATION, messages);

        assertThat(history.turns()).hasSize(1);
        assertThat(history.currentTurn().orElseThrow().messages()).hasSize(3);
    }

    @Test
    void namesTheAttachmentsWhenTheNameIsUnknown() {
        session(PHOTO, NOTES);

        assertThat(tool.execute(Map.of("name", "other.png")))
                .isEqualTo("No file named 'other.png'. Attached to this chat: photo.png, notes.md.");
        assertThat(conversations.appended).isEmpty();
    }

    @Test
    void pointsAtVectorSearchForAFileItCannotShow() {
        session(NOTES, AttachedFile.named("old.png"));

        assertThat(tool.execute(Map.of("name", "notes.md"))).contains("not an image or PDF", "vector_search");
        assertThat(tool.execute(Map.of("name", "old.png"))).contains("not an image or PDF that can be shown again");
        assertThat(tool.execute(Map.of())).startsWith("Error:");
        assertThat(conversations.appended).isEmpty();
    }

    // ── doubles ────────────────────────────────────────────────────────────

    private final class Conversations implements ConversationManager {
        final List<Message> appended = new ArrayList<>();

        @Override public Conversation createConversation(ConversationId id, String topic, ConversationType type,
                                                         List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Conversation> findById(ConversationId id) { throw new UnsupportedOperationException(); }
        @Override public List<Conversation> list(PageRequest page) {
            throw new UnsupportedOperationException();
        }
        @Override public List<Message> loadHistory(ConversationId id, PageRequest page) { return List.copyOf(messages); }
        @Override public ConversationHistory loadCompleteHistory(ConversationId id) {
            return ConversationHistory.of(id, messages);
        }
        @Override public Message addMessageToConversation(ConversationId id, String senderId, ParticipantType senderType,
                                                          MessageType type, String content, ChatTurnId turnId) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId id, String senderId, ParticipantType senderType,
                                                          MessageType type, String content, ChatTurnId turnId,
                                                          Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(ConversationId id, String senderId, ParticipantType senderType,
                                                          MessageType type, List<ContentPart> parts, ChatTurnId turnId,
                                                          Integer run, Map<String, Object> metadata) {
            Message m = Message.of(id, senderId, senderType, type, parts, messages.size() + 1)
                    .withTurnId(turnId).withMetadata(metadata);
            if (run != null) m = m.withRun(run);
            messages.add(m);
            appended.add(m);
            return m;
        }
        @Override public void compressMessage(ConversationId id, MessageId messageId, String stub, Integer tokens) { }
        @Override public void updateTokenCount(ConversationId id, MessageId messageId, int tokenCount) { }
        @Override public void updateDurationMs(ConversationId id, MessageId messageId, long durationMs) { }
        @Override public int deleteMessages(ConversationId id, int fromSeq, int toSeq) { return 0; }
    }

    private static final class Sessions implements AgentSessionRepository {
        AgentSession session;

        @Override public AgentSession save(AgentSession s) { session = s; return s; }
        @Override public Optional<AgentSession> findById(SessionId id) {
            return Optional.ofNullable(session).filter(s -> s.id().equals(id));
        }
        @Override public List<AgentSession> findByAgent(AgentId a, UserId u) { return List.of(); }
        @Override public List<AgentSession> findByUser(UserId u) { return List.of(); }
        @Override public List<AgentSession> findByParentSession(SessionId p) { return List.of(); }
        @Override public void deleteById(SessionId id) { }
    }
}
