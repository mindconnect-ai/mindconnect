package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ContentPart;
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
        sql.execute("DROP TABLE IF EXISTS mc_message_seq");
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
    void aMessageMadeOfPartsComesBackAsTheSameParts() {
        Message m = Message.of(conversation, sender, ParticipantType.USER, MessageType.CHAT, List.of(
                new ContentPart.Text("see attached"),
                new ContentPart.Image("f-1", "photo.png", "image/png", 240_000L),
                new ContentPart.File("f-2", "spec.pdf", "application/pdf", 1_048_576L)), 1);
        repo.save(m);

        assertThat(repo.findById(conversation, m.id())).contains(m);
        assertThat(repo.findById(conversation, m.id())).get()
                .extracting(Message::content).isEqualTo("see attached");
    }

    @Test
    void savingAgainReplacesTheRowNotAddsOne() {
        Message m = message(1);
        repo.save(m);
        Message compressed = m.withCompressed("[summary]", 5);
        repo.save(compressed);

        assertThat(repo.findByConversationId(conversation, PageRequest.DEFAULT)).hasSize(1);
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
        assertThat(repo.findByConversationId(conversation, PageRequest.DEFAULT)).hasSize(5);
        assertThat(repo.findByConversationId(UUID.randomUUID(), PageRequest.DEFAULT)).isEmpty();
    }

    @Test
    void deleteBySequenceRangeIsInclusiveAndScopedToTheConversation() {
        IntStream.rangeClosed(1, 6).forEach(seq -> repo.save(message(seq)));
        UUID other = UUID.randomUUID();
        repo.save(Message.of(other, sender, ParticipantType.USER, MessageType.CHAT, "keep", 3));

        repo.deleteBySequenceRange(conversation, 2, 4);

        assertThat(repo.findByConversationId(conversation, PageRequest.DEFAULT))
                .extracting(Message::sequenceNum).containsExactly(1, 5, 6);
        assertThat(repo.findByConversationId(other, PageRequest.DEFAULT)).hasSize(1);
        repo.deleteBySequenceRange(conversation, 100, 200); // nothing there is not an error
    }

    @Test
    void appendNumbersTheMessagesAndPicksUpFromWhatIsAlreadyStored() {
        repo.append(conversation, seq -> message(seq));
        repo.append(conversation, seq -> message(seq));

        assertThat(repo.findByConversationId(conversation, PageRequest.DEFAULT))
                .extracting(Message::sequenceNum).containsExactly(1, 2);

        // A conversation written before the counter existed: the first
        // append reads where it got to instead of starting over at 1.
        UUID older = UUID.randomUUID();
        IntStream.rangeClosed(1, 7).forEach(seq ->
                repo.save(Message.of(older, sender, ParticipantType.USER, MessageType.CHAT, "m" + seq, seq)));

        assertThat(repo.append(older, seq -> Message.of(older, sender, ParticipantType.USER,
                MessageType.CHAT, "next", seq)).sequenceNum()).isEqualTo(8);
    }

    @Test
    void twentyThreadsAppendingAtOnceGetTwentyDifferentNumbers() throws Exception {
        int writers = 20;
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(writers);
        var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        repo.append(conversation, seq -> message(seq));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).isEmpty();
        assertThat(repo.findByConversationId(conversation, new PageRequest(0, 100)))
                .extracting(Message::sequenceNum)
                .as("every appender left with a number of its own")
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, writers).boxed().toList());
    }
}
