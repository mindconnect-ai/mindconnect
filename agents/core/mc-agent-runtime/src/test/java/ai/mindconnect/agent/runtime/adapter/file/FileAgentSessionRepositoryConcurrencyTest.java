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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session is read and written from many threads at once — tool tasks
 * activate tools while the title generator names the chat and an upload
 * attaches a file. A reader must never see a half-written file, and no
 * writer may overwrite what another one wrote.
 */
class FileAgentSessionRepositoryConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void readsWhileUpdatingNeverSeeAHalfWrittenSession(@TempDir Path dir) throws Exception {
        FileAgentSessionRepository repo = new FileAgentSessionRepository(dir, MAPPER, new Namespace("test"));
        AgentSession session = repo.create(newSession());

        AtomicBoolean writing = new AtomicBoolean(true);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    String tool = "tool_" + i;
                    repo.update(session.id(), s -> s.withActivatedTools(List.of(tool)));
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

    @Test
    void concurrentUpdatesOfOneSessionAllLand(@TempDir Path dir) throws Exception {
        FileAgentSessionRepository repo = new FileAgentSessionRepository(dir, MAPPER, new Namespace("test"));
        AgentSession session = repo.create(newSession());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String tool = "tool_" + i;
                futures.add(pool.submit(() -> repo.update(session.id(), s -> s.withActivatedTools(List.of(tool)))));
                futures.add(pool.submit(() -> repo.update(session.id(), s -> s.withApprovedTool(tool))));
            }
            for (Future<?> future : futures) future.get();
        }

        AgentSession stored = repo.findById(session.id()).orElseThrow();
        String[] all = IntStream.range(0, 200).mapToObj(i -> "tool_" + i).toArray(String[]::new);
        assertThat(stored.activatedTools()).containsExactlyInAnyOrder(all);
        assertThat(stored.approvedTools()).containsExactlyInAnyOrder(all);
    }

    private static AgentSession newSession() {
        return AgentSession.startSubAgent(AgentId.random(), UserId.of("alice"), ConversationId.random(), null, null, null);
    }
}
