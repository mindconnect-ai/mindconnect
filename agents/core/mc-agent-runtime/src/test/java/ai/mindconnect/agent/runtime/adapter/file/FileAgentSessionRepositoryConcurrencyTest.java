package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * A session is read while other threads save it — a tool task loads it while
 * the title generator or a tool activation writes it. A reader must never see
 * a half-written file: that surfaced as tool tasks failing without a result.
 */
class FileAgentSessionRepositoryConcurrencyTest {

    @Test
    void readsWhileSavingNeverSeeAHalfWrittenSession(@TempDir Path dir) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        FileAgentSessionRepository repo = new FileAgentSessionRepository(dir, mapper, new Namespace("test"));
        AgentSession session = repo.save(AgentSession.startSubAgent(AgentId.random(), UserId.of("alice"),
                ConversationId.random(), null, null, null));

        AtomicBoolean writing = new AtomicBoolean(true);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    repo.save(session.withActivatedTools(List.of("tool_" + i)));
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
                assertThat(repo.findById(session.id())).isPresent();
                reads++;
            } catch (Throwable t) {
                failures.add(t);
                break;
            }
        }
        writer.join();

        assertThat(failures).isEmpty();
        assertThat(reads).isPositive();
        assertThat(repo.findById(session.id())).map(AgentSession::activatedTools)
                .hasValueSatisfying(tools -> assertThat(tools).contains("tool_399"));
    }
}
