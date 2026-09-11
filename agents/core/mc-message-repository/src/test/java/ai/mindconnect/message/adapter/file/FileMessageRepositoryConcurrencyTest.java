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
 * parallel ("No content to map due to end-of-input").
 */
class FileMessageRepositoryConcurrencyTest {

    @Test
    void historyReadsWhileSavingNeverSeeAHalfWrittenMessage(@TempDir Path dir) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FileMessageRepository repo = new FileMessageRepository(dir, mapper, new Namespace("test"));
        ConversationId conversation = ConversationId.random();
        Message message = repo.save(Message.of(conversation, "agent", ParticipantType.AGENT,
                MessageType.TOOL_RESULT, "x".repeat(20_000), 1));

        AtomicBoolean writing = new AtomicBoolean(true);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    repo.save(message.withTokenCount(i));
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
}
