package ai.mindconnect.message.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A conversation is read while other threads write into it — a tool task loads
 * the history while its sibling tasks save their results and the turn updates
 * token counts. A reader must never see a half-written message file: that
 * surfaced as tool tasks failing without a result when sub-agents ran in
 * parallel ("No content to map due to end-of-input"). And two changes of one
 * message must not overwrite each other.
 */
class FileMessageRepositoryConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void historyReadsWhileUpdatingNeverSeeAHalfWrittenMessage(@TempDir Path dir) throws Exception {
        FileMessageRepository repo = new FileMessageRepository(dir, MAPPER, new Namespace("test"));
        ConversationId conversation = ConversationId.random();
        Message message = repo.append(conversation, seq -> Message.of(conversation, "agent", ParticipantType.AGENT,
                MessageType.TOOL_RESULT, "x".repeat(20_000), seq));

        AtomicBoolean writing = new AtomicBoolean(true);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    int count = i;
                    repo.update(conversation, message.id(), m -> m.withTokenCount(count));
                }
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                writing.set(false);
            }
        });
        int reads = 0;
        while (writing.get()) {
            try {
                List<Message> history = repo.findByConversation(conversation, new PageRequest(0, 100));
                assertThat(history).hasSize(1);
                reads++;
            } catch (Throwable t) {
                failures.add(t);
                break;
            }
        }
        writer.join();

        assertThat(failures).isEmpty();
        assertThat(reads).isPositive();
        assertThat(repo.findById(conversation, message.id())).map(Message::tokenCount).contains(399);
    }

    @Test
    void aTokenCountAndADurationSetAtTheSameTimeBothLand(@TempDir Path dir) throws Exception {
        FileMessageRepository repo = new FileMessageRepository(dir, MAPPER, new Namespace("test"));
        ConversationId conversation = ConversationId.random();
        Message message = repo.append(conversation, seq -> Message.of(conversation, "agent", ParticipantType.AGENT,
                MessageType.TOOL_RESULT, "result", seq));
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        Thread tokens = Thread.ofPlatform().start(() -> run(failures, () -> {
            for (int i = 0; i < 200; i++) {
                int count = i;
                repo.update(conversation, message.id(), m -> m.withTokenCount(count));
            }
        }));
        Thread durations = Thread.ofPlatform().start(() -> run(failures, () -> {
            for (long i = 0; i < 200; i++) {
                long ms = i;
                repo.update(conversation, message.id(), m -> m.withDurationMs(ms));
            }
        }));
        tokens.join();
        durations.join();

        assertThat(failures).isEmpty();
        Message stored = repo.findById(conversation, message.id()).orElseThrow();
        assertThat(stored.tokenCount()).isEqualTo(199);
        assertThat(stored.durationMs()).isEqualTo(199L);
    }

    @Test
    void theNewestMessagesDeletedDoNotHandTheirNumbersOutAgain(@TempDir Path dir) {
        FileMessageRepository repo = new FileMessageRepository(dir, MAPPER, new Namespace("test"));
        ConversationId conversation = ConversationId.random();
        for (int i = 0; i < 5; i++) {
            repo.append(conversation, seq -> Message.of(conversation, "user", ParticipantType.USER,
                    MessageType.CHAT, "m" + seq, seq));
        }

        repo.deleteBySequenceRange(conversation, 4, 5);
        Message next = repo.append(conversation, seq -> Message.of(conversation, "user", ParticipantType.USER,
                MessageType.CHAT, "after", seq));

        assertThat(next.sequenceNum()).isEqualTo(6);
        assertThat(repo.findByConversation(conversation, new PageRequest(0, 10)))
                .extracting(Message::sequenceNum).containsExactly(1, 2, 3, 6);
    }

    private static void run(Queue<Throwable> failures, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }
}
