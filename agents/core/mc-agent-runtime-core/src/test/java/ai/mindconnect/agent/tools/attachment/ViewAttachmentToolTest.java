package ai.mindconnect.agent.tools.attachment;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.AttachedFile;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
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

    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID TURN = UUID.randomUUID();

    private static final AttachedFile PHOTO = new AttachedFile("f-1", "photo.png", "image/png", 240);
    private static final AttachedFile NOTES = new AttachedFile("f-3", "notes.md", "text/markdown", 12);

    /** The conversation so far: the user's question and the assistant's tool call, in one turn. */
    private final List<Message> messages = new ArrayList<>(List.of(
            Message.of(CONVERSATION, UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT, "show me", 1)
                    .withTurnId(TURN),
            Message.of(CONVERSATION, AGENT, ParticipantType.AGENT, MessageType.TOOL_CALL, "{}", 2)
                    .withTurnId(TURN).withRun(1)));

    private final Conversations conversations = new Conversations();
    private final Sessions sessions = new Sessions();
    private final ViewAttachmentTool tool = new ViewAttachmentTool(sessions, conversations, SESSION);

    private void session(AttachedFile... files) {
        sessions.session = new AgentSession(SESSION, AGENT, Namespace.DEFAULT, "u", CONVERSATION, "t",
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

        @Override public Conversation createConversation(Namespace ns, String topic, ConversationType type,
                                                         List<Participant> participants) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<Conversation> findById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public List<Conversation> listByNamespace(Namespace ns, PageRequest page) {
            throw new UnsupportedOperationException();
        }
        @Override public List<Message> loadHistory(UUID id, PageRequest page) { return List.copyOf(messages); }
        @Override public ConversationHistory loadCompleteHistory(UUID id) {
            return ConversationHistory.of(id, messages);
        }
        @Override public Message addMessageToConversation(UUID id, UUID senderId, ParticipantType senderType,
                                                          MessageType type, String content, UUID turnId) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(UUID id, UUID senderId, ParticipantType senderType,
                                                          MessageType type, String content, UUID turnId,
                                                          Integer run, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }
        @Override public Message addMessageToConversation(UUID id, UUID senderId, ParticipantType senderType,
                                                          MessageType type, List<ContentPart> parts, UUID turnId,
                                                          Integer run, Map<String, Object> metadata) {
            Message m = Message.of(id, senderId, senderType, type, parts, messages.size() + 1)
                    .withTurnId(turnId).withMetadata(metadata);
            if (run != null) m = m.withRun(run);
            messages.add(m);
            appended.add(m);
            return m;
        }
        @Override public void compressMessage(UUID id, UUID messageId, String stub, Integer tokens) { }
        @Override public void updateTokenCount(UUID id, UUID messageId, int tokenCount) { }
        @Override public void updateDurationMs(UUID id, UUID messageId, long durationMs) { }
        @Override public int deleteMessages(UUID id, int fromSeq, int toSeq) { return 0; }
    }

    private static final class Sessions implements AgentSessionRepository {
        AgentSession session;

        @Override public AgentSession save(AgentSession s) { session = s; return s; }
        @Override public Optional<AgentSession> findById(UUID id) {
            return Optional.ofNullable(session).filter(s -> s.id().equals(id));
        }
        @Override public List<AgentSession> findByAgentDefinitionId(UUID a, Namespace ns, String u) { return List.of(); }
        @Override public List<AgentSession> findByUser(Namespace ns, String u) { return List.of(); }
        @Override public List<AgentSession> findByParentSessionId(UUID p) { return List.of(); }
        @Override public void deleteById(UUID id) { }
    }
}
