package ai.mindconnect.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ContentPart;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.ConversationStatus;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.domain.Participant;
import ai.mindconnect.conversation.domain.ParticipantType;
import ai.mindconnect.conversation.port.out.ConversationChannel;
import ai.mindconnect.conversation.port.out.ConversationRepository;
import ai.mindconnect.conversation.port.out.MessageRepository;

/** A customer, the support and an assistant in one conversation, on its own page and in a channel. */
class ConversationServiceTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
    private static final PageRequest ALL = new PageRequest(0, 100);

    private final Map<ConversationId, Conversation> conversations = new HashMap<>();
    private final Map<ConversationId, List<Message>> messages = new HashMap<>();
    private final List<Message> delivered = new ArrayList<>();

    private final ConversationChannel whatsapp = new ConversationChannel() {
        @Override
        public String name() {
            return "whatsapp";
        }

        @Override
        public void deliver(Conversation conversation, Message message) {
            delivered.add(message);
        }
    };

    private final ConversationService service = new ConversationService(new ConversationRepository() {
        @Override
        public Conversation save(Conversation conversation) {
            conversations.put(conversation.id(), conversation);
            return conversation;
        }

        @Override
        public Optional<Conversation> findById(ConversationId id) {
            return Optional.ofNullable(conversations.get(id));
        }

        @Override
        public List<Conversation> findAll(PageRequest page) {
            return List.copyOf(conversations.values());
        }

        @Override
        public void deleteById(ConversationId id) {
            conversations.remove(id);
        }
    }, new MessageRepository() {
        @Override
        public Message append(ConversationId conversation, LongFunction<Message> create) {
            List<Message> held = messages.computeIfAbsent(conversation, id -> new ArrayList<>());
            Message message = create.apply(held.size() + 1);
            held.add(message);
            return message;
        }

        @Override
        public Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change) {
            return Optional.empty();
        }

        @Override
        public List<Message> findByConversation(ConversationId conversation, PageRequest page) {
            return List.copyOf(messages.getOrDefault(conversation, List.of()));
        }

        @Override
        public Optional<Message> findById(ConversationId conversation, MessageId id) {
            return Optional.empty();
        }

        @Override
        public void deleteByConversation(ConversationId conversation) {
            messages.remove(conversation);
        }
    }, List.of(whatsapp), NOW);

    @Test
    void people_and_a_bot_write_in_one_conversation_and_the_history_says_who_wrote_what() {
        Conversation opened = service.open("Order 4711", null, Map.of());
        Participant customer = service.join(opened.id(), ParticipantType.PERSON, "u-1", "Ada",
                Map.of("role", "customer"));
        Participant assistant = service.join(opened.id(), ParticipantType.BOT, "helper", "Assistant", Map.of());

        service.post(opened.id(), customer.id(), ContentPart.text("Where is my order?"), Map.of());
        service.post(opened.id(), assistant.id(), ContentPart.text("It ships today."),
                Map.of("agent.sessionId", "s-1", "agent.turnId", "t-1"));

        List<Message> history = service.history(opened.id(), ALL);
        assertThat(history).extracting(Message::sequence).containsExactly(1L, 2L);
        assertThat(history).extracting(Message::from).containsExactly(customer.id(), assistant.id());
        assertThat(history).extracting(Message::text).containsExactly("Where is my order?", "It ships today.");
        assertThat(history.get(1).metadata()).containsEntry("agent.sessionId", "s-1");
        assertThat(service.find(opened.id()).orElseThrow().participants())
                .extracting(Participant::type).containsExactly(ParticipantType.PERSON, ParticipantType.BOT);
    }

    @Test
    void what_is_written_here_goes_out_through_the_channel_and_what_came_in_does_not_go_back() {
        Conversation opened = service.open("Support", "whatsapp", Map.of("whatsapp.chatId", "4917"));
        Participant customer = service.join(opened.id(), ParticipantType.PERSON, "+49170", "Ada", Map.of());
        Participant support = service.join(opened.id(), ParticipantType.PERSON, "bob", "Bob", Map.of("role", "support"));

        service.receive(opened.id(), customer.id(), ContentPart.text("Hello?"), Map.of());
        Message answer = service.post(opened.id(), support.id(), ContentPart.text("Hi Ada"), Map.of());

        assertThat(delivered).containsExactly(answer);
    }

    @Test
    void a_participant_who_left_or_never_joined_cannot_write() {
        Conversation opened = service.open("Group", null, Map.of());
        Participant ada = service.join(opened.id(), ParticipantType.PERSON, "u-1", "Ada", Map.of());
        service.leave(opened.id(), ada.id());

        assertThatThrownBy(() -> service.post(opened.id(), ada.id(), ContentPart.text("still here?"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(ConversationId.random(), ada.id(), ContentPart.text("?"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.find(opened.id()).orElseThrow().participant(ada.id()).orElseThrow().leftAt())
                .isEqualTo(NOW.instant());
    }

    @Test
    void a_conversation_opens_only_in_a_channel_there_is() {
        assertThatThrownBy(() -> service.open("Team chat", "teams", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closing_keeps_the_conversation_and_deleting_takes_its_messages_with_it() {
        Conversation opened = service.open("Short", null, Map.of());
        Participant ada = service.join(opened.id(), ParticipantType.PERSON, "u-1", "Ada", Map.of());
        service.post(opened.id(), ada.id(), ContentPart.text("bye"), Map.of());

        service.close(opened.id());
        assertThat(service.find(opened.id()).orElseThrow().status()).isEqualTo(ConversationStatus.CLOSED);

        service.delete(opened.id());
        assertThat(service.find(opened.id())).isEmpty();
        assertThat(service.history(opened.id(), ALL)).isEmpty();
    }
}
