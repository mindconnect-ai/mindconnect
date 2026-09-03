package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PgMessageRepositoryTest {

    private final UUID conversation = UUID.randomUUID();
    private final UUID sender = UUID.randomUUID();

    private PgMessageRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_message");
        repo = new PgMessageRepository(sql).initSchema();
    }

    private Message message(int seq) {
        return Message.of(conversation, sender, ParticipantType.USER, MessageType.CHAT, "m" + seq, seq);
    }

    @Test
    void aMessageSurvivesTheRoundTripWithMetadataAndOptionalFields() {
        Message m = message(1)
                .withMetadata(Map.of("tool", "web", "nested", Map.of("k", List.of(1, 2))))
                .withTokenCount(42)
                .withDurationMs(1234L)
                .withTurnId(UUID.randomUUID())
                .withRun(2);
        repo.save(m);

        assertThat(repo.findById(conversation, m.id())).contains(m);
        assertThat(repo.findById(UUID.randomUUID(), m.id())).as("scoped to the conversation").isEmpty();
    }

    @Test
    void savingAgainReplacesTheRowNotAddsOne() {
        Message m = message(1);
        repo.save(m);
        Message compressed = m.withCompressed("[summary]", 5);
        repo.save(compressed);

        assertThat(repo.countByConversationId(conversation)).isEqualTo(1);
        assertThat(repo.findById(conversation, m.id())).contains(compressed);
        assertThat(repo.findById(conversation, m.id())).get()
                .extracting(Message::compressed, Message::compressedContent).containsExactly(true, "[summary]");
    }

    @Test
    void aConversationIsReadInSequenceOrderAndPaged() {
        List.of(3, 1, 2, 5, 4).forEach(seq -> repo.save(message(seq)));
        repo.save(Message.of(UUID.randomUUID(), sender, ParticipantType.AGENT, MessageType.CHAT, "other", 1));

        assertThat(repo.findByConversationId(conversation, new PageRequest(0, 3)))
                .extracting(Message::sequenceNum).containsExactly(1, 2, 3);
        assertThat(repo.findByConversationId(conversation, new PageRequest(1, 3)))
                .extracting(Message::sequenceNum).containsExactly(4, 5);
        assertThat(repo.countByConversationId(conversation)).isEqualTo(5);
        assertThat(repo.countByConversationId(UUID.randomUUID())).isZero();
    }

    @Test
    void deleteBySequenceRangeIsInclusiveAndScopedToTheConversation() {
        IntStream.rangeClosed(1, 6).forEach(seq -> repo.save(message(seq)));
        UUID other = UUID.randomUUID();
        repo.save(Message.of(other, sender, ParticipantType.USER, MessageType.CHAT, "keep", 3));

        repo.deleteBySequenceRange(conversation, 2, 4);

        assertThat(repo.findByConversationId(conversation, PageRequest.DEFAULT))
                .extracting(Message::sequenceNum).containsExactly(1, 5, 6);
        assertThat(repo.countByConversationId(other)).isEqualTo(1);
        repo.deleteBySequenceRange(conversation, 100, 200); // nothing there is not an error
    }
}
