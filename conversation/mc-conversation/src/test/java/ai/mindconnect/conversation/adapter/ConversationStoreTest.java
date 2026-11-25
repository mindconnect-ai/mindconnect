package ai.mindconnect.conversation.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.adapter.file.FileConversationRepository;
import ai.mindconnect.conversation.adapter.file.FileMessageRepository;
import ai.mindconnect.conversation.adapter.memory.InMemoryConversationRepository;
import ai.mindconnect.conversation.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.conversation.domain.ContentPart;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.ConversationStatus;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.domain.Participant;
import ai.mindconnect.conversation.domain.ParticipantId;
import ai.mindconnect.conversation.domain.ParticipantType;
import ai.mindconnect.conversation.port.out.ConversationRepository;
import ai.mindconnect.conversation.port.out.MessageRepository;

/** What both stores of this module keep: conversations with their participants, messages in order. */
class ConversationStoreTest {

    static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));
    static final PageRequest ALL = new PageRequest(0, 1000);

    abstract static class Contract {

        abstract ConversationRepository conversations();

        abstract MessageRepository messages();

        private static Conversation conversation(String topic, Instant created) {
            ConversationId id = ConversationId.random();
            Participant ada = new Participant(ParticipantId.random(), id, ParticipantType.PERSON, "u-1", "Ada",
                    Map.of("role", "customer"), created, null);
            return new Conversation(id, topic, "whatsapp", ConversationStatus.OPEN, List.of(ada),
                    Map.of("whatsapp.chatId", "4917"), created, created);
        }

        private static Message message(ConversationId conversation, long place, String text) {
            return new Message(MessageId.random(), conversation, place, ParticipantId.random(),
                    List.of(new ContentPart.Text(text), new ContentPart.Image("f-1", "a.png", "image/png", 3)),
                    Instant.parse("2026-11-25T12:00:00Z"), Map.of("agent.sessionId", "s-1"));
        }

        @Test
        void a_conversation_comes_back_as_it_was_saved_and_the_newest_is_listed_first() {
            Conversation older = conversations().save(conversation("older", Instant.parse("2026-11-01T00:00:00Z")));
            Conversation newer = conversations().save(conversation("newer", Instant.parse("2026-11-02T00:00:00Z")));

            assertThat(conversations().findById(older.id())).contains(older);
            assertThat(conversations().findAll(ALL)).containsExactly(newer, older);

            conversations().deleteById(older.id());
            assertThat(conversations().findById(older.id())).isEmpty();
        }

        @Test
        void messages_come_back_in_order_as_they_were_written_and_their_metadata_changes_in_place() {
            ConversationId id = ConversationId.random();
            Message first = messages().append(id, place -> message(id, place, "one"));
            Message second = messages().append(id, place -> message(id, place, "two"));

            assertThat(messages().findByConversation(id, ALL)).containsExactly(first, second);
            assertThat(List.of(first.sequence(), second.sequence())).containsExactly(1L, 2L);
            assertThat(messages().findById(id, second.id())).contains(second);

            Message changed = messages().update(id, first.id(), m -> m.withMetadata("whatsapp.messageId", "w-1"))
                    .orElseThrow();
            assertThat(messages().findById(id, first.id())).contains(changed);
            assertThat(changed.metadata()).containsEntry("agent.sessionId", "s-1")
                    .containsEntry("whatsapp.messageId", "w-1");

            messages().deleteByConversation(id);
            assertThat(messages().findByConversation(id, ALL)).isEmpty();
        }

        @Test
        void appends_at_the_same_time_get_places_of_their_own() throws InterruptedException {
            ConversationId id = ConversationId.random();
            Set<Long> places = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            for (int i = 0; i < 40; i++) {
                pool.submit(() -> places.add(messages().append(id, place -> message(id, place, "x")).sequence()));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(places).hasSize(40);
            assertThat(messages().findByConversation(id, ALL)).hasSize(40);
        }
    }

    @Nested
    class InMemory extends Contract {
        private final ConversationRepository conversations = new InMemoryConversationRepository(SCOPE);
        private final MessageRepository messages = new InMemoryMessageRepository(SCOPE);

        @Override
        ConversationRepository conversations() {
            return conversations;
        }

        @Override
        MessageRepository messages() {
            return messages;
        }
    }

    @Nested
    class OnFiles extends Contract {
        @TempDir
        Path directory;

        private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

        @Override
        ConversationRepository conversations() {
            return new FileConversationRepository(directory, json, SCOPE);
        }

        @Override
        MessageRepository messages() {
            return new FileMessageRepository(directory, json, SCOPE);
        }
    }
}
