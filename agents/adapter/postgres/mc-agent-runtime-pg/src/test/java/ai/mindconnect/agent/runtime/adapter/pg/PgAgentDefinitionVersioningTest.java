package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.common.StaleVersionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Versions on Postgres: the same checks as the file store, the row read {@code FOR UPDATE}. */
class PgAgentDefinitionVersioningTest {

    private PgAgentDefinitionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgAgentDefinitionRepository(TestDb.fresh("mc_agent_definition"), new Namespace("test")).initSchema();
    }

    @Test
    void aSaveAgainstAStaleVersionIsRefused() throws Exception {
        AgentDefinition created = repo.save(AgentDefinition.create("scout", "d", "prompt", "hi", "cfg"));
        assertThat(created.version()).isEqualTo(1L);
        AgentDefinition renamed = repo.save(created.withBasicFields("scout-2", "d", "prompt", "hi", "cfg"));
        assertThat(renamed.version()).isEqualTo(2L);

        assertThatThrownBy(() -> repo.save(created.withBasicFields("stale", "d", "prompt", "hi", "cfg")))
                .isInstanceOf(StaleVersionException.class);
        assertThat(repo.findById(created.id())).map(AgentDefinition::name).contains("scout-2");
        assertThat(repo.save(created.withVersion(null)).version()).as("no version: no check").isEqualTo(3L);

        AgentDefinition read = repo.findById(created.id()).orElseThrow();
        AtomicInteger landed = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String name = "edit-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        repo.save(read.withBasicFields(name, "d", "prompt", "hi", "cfg"));
                        landed.incrementAndGet();
                    } catch (StaleVersionException e) {
                        // the others were first
                    }
                }));
            }
            for (Future<?> future : futures) future.get();
        }
        assertThat(landed.get()).isEqualTo(1);
        assertThat(repo.findById(created.id())).map(AgentDefinition::version).contains(4L);
    }
}
