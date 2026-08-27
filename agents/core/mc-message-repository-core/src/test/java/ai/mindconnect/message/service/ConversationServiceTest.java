package ai.mindconnect.message.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
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
import java.util.UUID;

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
        Namespace ns = new Namespace("test");
        List<Participant> participants = List.of(
                Participant.user(UUID.randomUUID(), "user-1", "Alice"),
                Participant.agent(UUID.randomUUID(), "agent-1", "Bot")
        );

        Conversation result = service.createConversation(ns, "Support Chat", ConversationType.USER_AGENT, participants);

        assertThat(result.id()).isNotNull();
        assertThat(result.namespace()).isEqualTo(ns);
        assertThat(result.topic()).isEqualTo("Support Chat");
        assertThat(result.participants()).hasSize(2);
    }

    @Test
    void addMessage_ToConversation_appendsToConversation() {
        Conversation conv = service.createConversation(new Namespace("test"), "topic",
                ConversationType.USER_AGENT, List.of());

        UUID senderId = UUID.randomUUID();
        Message msg = service.addMessageToConversation(conv.id(), senderId, ParticipantType.USER, MessageType.CHAT, "Hello!", null);

        assertThat(msg.content()).isEqualTo("Hello!");
        assertThat(msg.sequenceNum()).isEqualTo(1);
        assertThat(msg.conversationId()).isEqualTo(conv.id());
    }

    @Test
    void addMessage_ToConversation_incrementsSequence() {
        Conversation conv = service.createConversation(new Namespace("test"), "topic",
                ConversationType.USER_AGENT, List.of());
        UUID sender = UUID.randomUUID();

        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "msg 1", null);
        Message msg2 = service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "msg 2", null);

        assertThat(msg2.sequenceNum()).isEqualTo(2);
    }

    @Test
    void addMessage_ToConversation_throwsWhenConversationNotFound() {
        assertThatThrownBy(() -> service.addMessageToConversation(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT, "x", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void loadHistory_returnsMessagesInOrder() {
        Conversation conv = service.createConversation(new Namespace("test"), "topic",
                ConversationType.USER_AGENT, List.of());
        UUID sender = UUID.randomUUID();
        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "first", null);
        service.addMessageToConversation(conv.id(), sender, ParticipantType.USER, MessageType.CHAT, "second", null);

        List<Message> history = service.loadHistory(conv.id(), new PageRequest(0, 10));

        assertThat(history).hasSize(2);
        assertThat(history.get(0).content()).isEqualTo("first");
        assertThat(history.get(1).content()).isEqualTo("second");
    }

    @Test
    void listByNamespace_filtersCorrectly() {
        Namespace ns1 = new Namespace("ns1");
        Namespace ns2 = new Namespace("ns2");
        service.createConversation(ns1, "a", ConversationType.USER_AGENT, List.of());
        service.createConversation(ns1, "b", ConversationType.USER_AGENT, List.of());
        service.createConversation(ns2, "c", ConversationType.USER_AGENT, List.of());

        List<Conversation> result = service.listByNamespace(ns1, new PageRequest(0, 10));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(c -> c.namespace().equals(ns1));
    }

    // --- minimal in-memory test doubles ---

    static class InMemoryConversationRepository implements ConversationRepository {
        final Map<UUID, Conversation> store = new HashMap<>();

        @Override
        public Conversation save(Conversation c) { store.put(c.id(), c); return c; }

        @Override
        public Optional<Conversation> findById(UUID id) { return Optional.ofNullable(store.get(id)); }

        @Override
        public List<Conversation> findByNamespace(Namespace ns, PageRequest page) {
            return store.values().stream()
                    .filter(c -> c.namespace().equals(ns))
                    .skip((long) page.page() * page.size()).limit(page.size()).toList();
        }
    }

    static class InMemoryMessageRepository implements MessageRepository {
        final List<Message> store = new ArrayList<>();

        @Override
        public Message save(Message m) { store.add(m); return m; }

        @Override
        public List<Message> findByConversationId(UUID id, PageRequest page) {
            return store.stream()
                    .filter(m -> m.conversationId().equals(id))
                    .sorted((a, b) -> Integer.compare(a.sequenceNum(), b.sequenceNum()))
                    .skip((long) page.page() * page.size()).limit(page.size()).toList();
        }

        @Override
        public java.util.Optional<Message> findById(UUID conversationId, UUID messageId) {
            return store.stream().filter(m -> m.id().equals(messageId)).findFirst();
        }

        @Override
        public int countByConversationId(UUID id) {
            return (int) store.stream().filter(m -> m.conversationId().equals(id)).count();
        }

        @Override
        public void deleteBySequenceRange(UUID conversationId, int fromSeq, int toSeq) {
            store.removeIf(m -> m.conversationId().equals(conversationId)
                    && m.sequenceNum() >= fromSeq && m.sequenceNum() <= toSeq);
        }
    }
}
