package ai.mindconnect.conversation.adapter.pg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ContentPart;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.ConversationStatus;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.domain.Participant;
import ai.mindconnect.conversation.domain.ParticipantId;
import ai.mindconnect.conversation.domain.ParticipantType;
import ai.mindconnect.jdbc.Sql;

/** Conversations and their messages against a real Postgres, or skipped. */
class PgConversationStoreTest {

    private static final PageRequest ALL = new PageRequest(0, 1000);

    private Sql sql;

    @BeforeEach
    void emptyTables() {
        sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_conversation_thread, mc_conversation_entry, mc_conversation_entry_seq");
    }

    private PgConversationRepository conversations(String namespace) {
        return new PgConversationRepository(sql, ScopeSupplier.fixed(new Namespace(namespace))).initSchema();
    }

    private PgMessageRepository messages(String namespace) {
        return new PgMessageRepository(sql, ScopeSupplier.fixed(new Namespace(namespace))).initSchema();
    }

    private static Message message(ConversationId conversation, long place, String text) {
        return new Message(MessageId.random(), conversation, place, ParticipantId.random(),
                List.of(new ContentPart.Text(text), new ContentPart.File("f-1", "a.pdf", "application/pdf", 9)),
                Instant.parse("2026-11-25T12:00:00Z"), Map.of("agent.turnId", "t-1"));
    }

    @Test
    void a_conversation_comes_back_with_its_participants_and_only_in_its_namespace() {
        ConversationId id = ConversationId.random();
        Instant at = Instant.parse("2026-11-25T10:00:00Z");
        Conversation saved = conversations("a").save(new Conversation(id, "Order", "whatsapp", ConversationStatus.OPEN,
                List.of(new Participant(ParticipantId.random(), id, ParticipantType.BOT, "helper", "Assistant",
                        Map.of(), at, null)), Map.of("whatsapp.chatId", "4917"), at, at));

        assertThat(conversations("a").findById(id)).contains(saved);
        assertThat(conversations("a").findAll(ALL)).containsExactly(saved);
        assertThat(conversations("b").findById(id)).isEmpty();

        conversations("a").deleteById(id);
        assertThat(conversations("a").findById(id)).isEmpty();
    }

    @Test
    void messages_keep_their_order_parts_and_metadata_and_change_in_place() {
        PgMessageRepository messages = messages("a");
        ConversationId id = ConversationId.random();
        Message first = messages.append(id, place -> message(id, place, "one"));
        Message second = messages.append(id, place -> message(id, place, "two"));

        assertThat(messages.findByConversation(id, ALL)).containsExactly(first, second);
        assertThat(second.sequence()).isEqualTo(2);
        Message changed = messages.update(id, first.id(), m -> m.withMetadata("teams.messageId", "x")).orElseThrow();
        assertThat(messages.findById(id, first.id())).contains(changed);
        assertThat(messages("b").findByConversation(id, ALL)).isEmpty();

        messages.deleteByConversation(id);
        assertThat(messages.findByConversation(id, ALL)).isEmpty();
        assertThat(messages.append(id, place -> message(id, place, "after")).sequence()).isEqualTo(3);
    }

    @Test
    void appends_at_the_same_time_get_places_of_their_own() throws InterruptedException {
        PgMessageRepository messages = messages("a");
        ConversationId id = ConversationId.random();
        Set<Long> places = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> places.add(messages.append(id, place -> message(id, place, "x")).sequence()));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(places).hasSize(40);
    }
}
