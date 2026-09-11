package ai.mindconnect.message.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.port.out.ConversationRepository;
import ai.mindconnect.message.port.out.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationServiceTest {

    private ConversationService service;
    private InMemoryConversationRepository conversationRepo;
    private InMemoryMessageRepository messageRepo;

    @BeforeEach
    void setUp() {
        conversationRepo = new InMemoryConversationRepository();
        messageRepo = new InMemoryMessageRepository();
        service = new ConversationService(conversationRepo, messageRepo);
    }

    @Test
    void createConversation_savesAndReturnsConversation() {
        ConversationId conversation = ConversationId.random();
        List<Participant> participants = List.of(
                Participant.user(conversation, UserId.of("user-1"), "Alice"),
                Participant.agent(conversation, AgentId.of("agent-1"), "Bot")
        );

        Conversation result = service.createConversation(conversation, "Support Chat", ConversationType.USER_AGENT, participants);

        assertThat(result.id()).isNotNull();
        assertThat(result.topic()).isEqualTo("Support Chat");
        assertThat(result.participants()).hasSize(2);
    }

    @Test
    void addMessage_ToConversation_appendsToConversation() {
        Conversation conv = service.createConversation(ConversationId.random(), "topic",
                ConversationType.USER_AGENT, List.of());

        String senderId = "user-1";
        Message msg = service.addMessageToConversation(conv.id(), senderId, ParticipantType.USER, MessageType.CHAT, "Hello!", null);

        assertThat(msg.content()).isEqualTo("Hello!");
        assertThat(msg.sequenceNum()).isEqualTo(1);
        assertThat(msg.conversationId()).isEqualTo(conv.id());
    }

    @Test
    void addMessage_ToConversation_incrementsSequence() {
        Conversation conv = service.createConversation(ConversationId.random(), "topic",
                ConversationType.USER_AGENT, List.of());
        String sender = "user-1";

        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "msg 1", null);
        Message msg2 = service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "msg 2", null);

        assertThat(msg2.sequenceNum()).isEqualTo(2);
    }

    @Test
    void addMessage_withParts_derivesContentFromTheTextParts() {
        Conversation conv = service.createConversation(ConversationId.random(), "topic",
                ConversationType.USER_AGENT, List.of());
        List<ContentPart> parts = List.of(
                new ContentPart.Text("What is in this picture?"),
                new ContentPart.Image("f-1", "photo.png", "image/png", 240_000L));

        Message msg = service.addMessageToConversation(conv.id(), "user-1", ParticipantType.USER,
                MessageType.CHAT, parts, null, 0, Map.of());

        assertThat(msg.content()).isEqualTo("What is in this picture?");
        assertThat(msg.parts()).containsExactlyElementsOf(parts);
        assertThat(msg.partsOrText()).containsExactlyElementsOf(parts);
        assertThat(msg.sequenceNum()).isEqualTo(1);
        assertThat(messageRepo.store).containsExactly(msg);
    }

    @Test
    void addMessage_withText_hasNoPartsButReadsAsOneTextPart() {
        Conversation conv = service.createConversation(ConversationId.random(), "topic",
                ConversationType.USER_AGENT, List.of());

        Message msg = service.addMessageToConversation(conv.id(), "user-1", ParticipantType.USER,
                MessageType.CHAT, "Hello!", null);

        assertThat(msg.parts()).isNull();
        assertThat(msg.partsOrText()).containsExactly(new ContentPart.Text("Hello!"));
    }

    @Test
    void addMessage_withParts_appendsAfterExistingMessages() {
        Conversation conv = service.createConversation(ConversationId.random(), "topic",
                ConversationType.USER_AGENT, List.of());
        String sender = "user-1";
        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "first", null);

        Message msg = service.addMessageToConversation(conv.id(), sender, ParticipantType.USER,
                MessageType.CHAT, ContentPart.text("second"), null, null, Map.of());

        assertThat(msg.sequenceNum()).isEqualTo(2);
        assertThat(msg.content()).isEqualTo("second");
    }

    @Test
    void addMessage_ToConversation_throwsWhenConversationNotFound() {
        assertThatThrownBy(() -> service.addMessageToConversation(ConversationId.random(), "user-1", ParticipantType.USER, MessageType.CHAT, "x", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void loadHistory_returnsMessagesInOrder() {
        Conversation conv = service.createConversation(ConversationId.random(), "topic",
                ConversationType.USER_AGENT, List.of());
        String sender = "user-1";
        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "first", null);
        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "second", null);

        List<Message> history = service.loadHistory(conv.id(), new PageRequest(0, 10));

        assertThat(history).hasSize(2);
        assertThat(history.get(0).content()).isEqualTo("first");
        assertThat(history.get(1).content()).isEqualTo("second");
    }

    // --- minimal in-memory test doubles ---

    static class InMemoryConversationRepository implements ConversationRepository {
        final Map<ConversationId, Conversation> store = new HashMap<>();

        @Override
        public Conversation save(Conversation c) { store.put(c.id(), c); return c; }

        @Override
        public Optional<Conversation> findById(ConversationId id) { return Optional.ofNullable(store.get(id)); }

        @Override
        public List<Conversation> findAll(PageRequest page) {
            return store.values().stream()
                    .skip((long) page.page() * page.size()).limit(page.size()).toList();
        }
    }

    static class InMemoryMessageRepository implements MessageRepository {
        final List<Message> store = new ArrayList<>();

        @Override
        public Message save(Message m) { store.add(m); return m; }

        @Override
        public synchronized java.util.Optional<Message> update(ConversationId conversation, MessageId id,
                                                             java.util.function.UnaryOperator<Message> change) {
            for (int i = 0; i < store.size(); i++) {
                Message m = store.get(i);
                if (m.conversationId().equals(conversation) && m.id().equals(id)) {
                    Message changed = change.apply(m);
                    store.set(i, changed);
                    return java.util.Optional.of(changed);
                }
            }
            return java.util.Optional.empty();
        }

        @Override
        public List<Message> findByConversation(ConversationId id, PageRequest page) {
            return store.stream()
                    .filter(m -> m.conversationId().equals(id))
                    .sorted((a, b) -> Integer.compare(a.sequenceNum(), b.sequenceNum()))
                    .skip((long) page.page() * page.size()).limit(page.size()).toList();
        }

        @Override
        public java.util.Optional<Message> findById(ConversationId conversation, MessageId id) {
            return store.stream()
                    .filter(m -> m.conversationId().equals(conversation) && m.id().equals(id))
                    .findFirst();
        }

        @Override
        public synchronized Message append(ConversationId id, java.util.function.IntFunction<Message> create) {
            int next = store.stream()
                    .filter(m -> m.conversationId().equals(id))
                    .mapToInt(Message::sequenceNum)
                    .max().orElse(0) + 1;
            return save(create.apply(next));
        }

        @Override
        public void deleteBySequenceRange(ConversationId conversationId, int fromSeq, int toSeq) {
            store.removeIf(m -> m.conversationId().equals(conversationId)
                    && m.sequenceNum() >= fromSeq && m.sequenceNum() <= toSeq);
        }
    }
}
