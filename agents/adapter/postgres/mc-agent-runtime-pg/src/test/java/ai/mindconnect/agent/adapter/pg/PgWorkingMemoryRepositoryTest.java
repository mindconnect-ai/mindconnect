package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PgWorkingMemoryRepositoryTest {

    private static final AuthenticationInfo DAVID = AuthenticationInfo.of("david", new Namespace("default"));
    private static final AuthenticationInfo EVE = AuthenticationInfo.of("eve", new Namespace("default"));

    private final UUID session = UUID.randomUUID();
    private PgWorkingMemoryRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgWorkingMemoryRepository(TestDb.fresh("mc_working_memory")).initSchema();
    }

    private static WorkingMemory memory(String prompt) {
        return new WorkingMemory(prompt, 3, List.of(
                new WorkingMemory.WorkingMemoryMessage("CHAT", "user", 1, 1_000L, 5, "hi", false, null)),
                8, "cl100k", 128_000);
    }

    @Test
    void memoryAndSummaryLiveSideBySideWithSeparateLifecycles() {
        repo.saveSummary(session, DAVID, "  the gist  ");
        assertThat(repo.findBySessionId(session, DAVID)).as("summary alone is not a memory").isEmpty();
        assertThat(repo.loadSummary(session, DAVID)).contains("the gist");

        repo.save(session, DAVID, memory("v1"));
        repo.save(session, DAVID, memory("v2"));
        assertThat(repo.findBySessionId(session, DAVID)).contains(memory("v2"));
        assertThat(repo.loadSummary(session, DAVID)).as("saving memory keeps the summary").contains("the gist");

        repo.deleteSummary(session, DAVID);
        assertThat(repo.loadSummary(session, DAVID)).isEmpty();
        assertThat(repo.findBySessionId(session, DAVID)).as("deleting the summary keeps the memory").contains(memory("v2"));

        repo.saveSummary(session, DAVID, "   ");
        assertThat(repo.loadSummary(session, DAVID)).as("blank reads as absent").isEmpty();

        repo.delete(session, DAVID);
        assertThat(repo.findBySessionId(session, DAVID)).isEmpty();
        assertThat(repo.loadSummary(session, DAVID)).isEmpty();
    }

    @Test
    void anotherUserNeitherSeesNorOverwritesTheSessionsMemory() {
        repo.save(session, DAVID, memory("mine"));

        assertThat(repo.findBySessionId(session, EVE)).isEmpty();
        repo.save(session, EVE, memory("hers"));
        repo.saveSummary(session, EVE, "hers");
        repo.delete(session, EVE);

        assertThat(repo.findBySessionId(session, DAVID)).contains(memory("mine"));
        assertThat(repo.loadSummary(session, DAVID)).isEmpty();
    }
}
