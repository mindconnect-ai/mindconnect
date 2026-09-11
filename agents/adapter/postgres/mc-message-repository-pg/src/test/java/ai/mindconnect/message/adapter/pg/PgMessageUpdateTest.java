package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/** A token count and a duration set on one message at the same time both land — on Postgres as in the file store. */
class PgMessageUpdateTest {

    private final ConversationId conversation = ConversationId.random();

    private PgMessageRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_message");
        sql.execute("DROP TABLE IF EXISTS mc_message_seq");
        repo = new PgMessageRepository(sql, new Namespace("test")).initSchema();
    }

    @Test
    void concurrentChangesOfOneMessageBothLand() throws Exception {
        Message message = repo.append(conversation, seq -> Message.of(conversation, "agent", ParticipantType.AGENT,
                MessageType.TOOL_RESULT, "result", seq));
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        Thread tokens = Thread.ofPlatform().start(() -> run(failures, () -> {
            for (int i = 0; i < 50; i++) {
                int count = i;
                repo.update(conversation, message.id(), m -> m.withTokenCount(count));
            }
        }));
        Thread durations = Thread.ofPlatform().start(() -> run(failures, () -> {
            for (long i = 0; i < 50; i++) {
                long ms = i;
                repo.update(conversation, message.id(), m -> m.withDurationMs(ms));
            }
        }));
        tokens.join();
        durations.join();

        assertThat(failures).isEmpty();
        Message stored = repo.findById(conversation, message.id()).orElseThrow();
        assertThat(stored.tokenCount()).isEqualTo(49);
        assertThat(stored.durationMs()).isEqualTo(49L);
    }

    @Test
    void updatingAMissingMessageChangesNothing() {
        assertThat(repo.update(conversation, MessageId.random(), m -> m.withTokenCount(1))).isEmpty();
    }

    private static void run(Queue<Throwable> failures, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }
}
