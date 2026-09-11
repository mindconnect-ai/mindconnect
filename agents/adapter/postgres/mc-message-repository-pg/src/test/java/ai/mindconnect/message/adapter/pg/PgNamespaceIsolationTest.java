package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.Participant;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository is bound to one namespace: what is written through the one
 * bound to A is invisible — to find, list and delete — through the one bound
 * to B on the same tables, and A still finds it afterwards.
 */
class PgNamespaceIsolationTest {

    private static final Namespace A = new Namespace("team-a");
    private static final Namespace B = new Namespace("team-b");

    private Sql sql;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_conversation");
        sql.execute("DROP TABLE IF EXISTS mc_message");
        sql.execute("DROP TABLE IF EXISTS mc_message_seq");
    }

    @Test
    void conversationsAndMessagesOfAnotherNamespaceAreInvisible() {
        var conversationsA = new PgConversationRepository(sql, A).initSchema();
        var conversationsB = new PgConversationRepository(sql, B).initSchema();
        var messagesA = new PgMessageRepository(sql, A).initSchema();
        var messagesB = new PgMessageRepository(sql, B).initSchema();

        ConversationId id = ConversationId.random();
        Conversation conversation = Conversation.create(id, "topic", ConversationType.USER_AGENT,
                List.of(Participant.user(id, UserId.of("david"), "David")));
        conversationsA.save(conversation);
        Message first = messagesA.append(id, seq -> message(id, "a" + seq, seq));
        messagesA.append(id, seq -> message(id, "a" + seq, seq));

        assertThat(conversationsB.findById(id)).isEmpty();
        assertThat(conversationsB.findAll(PageRequest.DEFAULT)).isEmpty();
        assertThat(messagesB.findById(id, first.id())).isEmpty();
        assertThat(messagesB.findByConversation(id, PageRequest.DEFAULT)).isEmpty();
        messagesB.deleteBySequenceRange(id, 1, 100);

        // B's sequence counter for the same conversation id starts on its own
        assertThat(messagesB.append(id, seq -> message(id, "b" + seq, seq)).sequenceNum()).isEqualTo(1);
        // and the same message id may exist once per namespace
        messagesB.save(first);
        assertThat(messagesB.findByConversation(id, PageRequest.DEFAULT)).hasSize(2);

        assertThat(conversationsA.findById(id)).contains(conversation);
        assertThat(messagesA.findById(id, first.id())).contains(first);
        assertThat(messagesA.findByConversation(id, PageRequest.DEFAULT))
                .extracting(Message::content).containsExactly("a1", "a2");
        assertThat(messagesA.append(id, seq -> message(id, "a" + seq, seq)).sequenceNum()).isEqualTo(3);
    }

    private static Message message(ConversationId conversation, String content, int seq) {
        return Message.of(conversation, "sender", ParticipantType.USER, MessageType.CHAT, content, seq);
    }
}
