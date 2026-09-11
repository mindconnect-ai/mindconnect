package ai.mindconnect.agent.runtime.adapter;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.adapter.file.FileAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.StaleVersionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two admins edit the same agent: the second save, made against the version both
 * opened, is refused instead of overwriting the first. File and in-memory stores
 * behave the same.
 */
class AgentDefinitionVersioningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void theFileStoreChecksVersions(@TempDir Path dir) throws Exception {
        versionsAreChecked(new FileAgentDefinitionRepository(dir, MAPPER, new Namespace("test")));
    }

    @Test
    void theInMemoryStoreChecksVersions() throws Exception {
        versionsAreChecked(new InMemoryAgentDefinitionRepository());
    }

    static void versionsAreChecked(AgentDefinitionRepository repo) throws Exception {
        AgentDefinition created = repo.save(AgentDefinition.create("scout", "d", "prompt", "hi", "cfg"));
        assertThat(created.version()).isEqualTo(1L);

        AgentDefinition renamed = repo.save(created.withBasicFields("scout-2", "d", "prompt", "hi", "cfg"));
        assertThat(renamed.version()).isEqualTo(2L);
        assertThat(repo.findById(created.id())).map(AgentDefinition::version).contains(2L);

        // A form still showing version 1.
        assertThatThrownBy(() -> repo.save(created.withBasicFields("stale", "d", "prompt", "hi", "cfg")))
                .isInstanceOf(StaleVersionException.class);
        assertThat(repo.findById(created.id())).map(AgentDefinition::name).contains("scout-2");

        // No version: no check, as a seed or an import saves.
        AgentDefinition seeded = repo.save(created.withVersion(null)
                .withBasicFields("seeded", "d", "prompt", "hi", "cfg"));
        assertThat(seeded.version()).isEqualTo(3L);

        // Twenty saves from the same read: exactly one lands.
        AgentDefinition read = repo.findById(created.id()).orElseThrow();
        AtomicInteger landed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String name = "edit-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        repo.save(read.withBasicFields(name, "d", "prompt", "hi", "cfg"));
                        landed.incrementAndGet();
                    } catch (StaleVersionException e) {
                        refused.incrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        }
        assertThat(landed.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(19);
        assertThat(repo.findById(created.id())).map(AgentDefinition::version).contains(4L);
    }
}
