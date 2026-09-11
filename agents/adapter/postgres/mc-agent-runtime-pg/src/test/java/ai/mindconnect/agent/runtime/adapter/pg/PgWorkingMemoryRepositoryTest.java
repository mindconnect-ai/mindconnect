package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PgWorkingMemoryRepositoryTest {

    private static final Namespace NS = new Namespace("test");
    private static final AuthenticationInfo DAVID = AuthenticationInfo.of(UserId.of("david"));
    private static final AuthenticationInfo EVE = AuthenticationInfo.of(UserId.of("eve"));

    private final SessionId session = SessionId.random();
    private PgWorkingMemoryRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgWorkingMemoryRepository(TestDb.fresh("mc_working_memory"), NS).initSchema();
    }

    private static WorkingMemory memory(String prompt) {
        return new WorkingMemory(prompt, 3, List.of(
                new WorkingMemory.WorkingMemoryMessage("CHAT", "user", 1, 1_000L, 5, "hi", false, null)),
                8, "cl100k", 128_000);
    }

    @Test
    void memoryAndSummaryLiveSideBySideWithSeparateLifecycles() {
        repo.saveSummary(session, DAVID, "  the gist  ");
        assertThat(repo.findBySession(session, DAVID)).as("summary alone is not a memory").isEmpty();
        assertThat(repo.loadSummary(session, DAVID)).contains("the gist");

        repo.save(session, DAVID, memory("v1"));
        repo.save(session, DAVID, memory("v2"));
        assertThat(repo.findBySession(session, DAVID)).contains(memory("v2"));
        assertThat(repo.loadSummary(session, DAVID)).as("saving memory keeps the summary").contains("the gist");

        repo.deleteSummary(session, DAVID);
        assertThat(repo.loadSummary(session, DAVID)).isEmpty();
        assertThat(repo.findBySession(session, DAVID)).as("deleting the summary keeps the memory").contains(memory("v2"));

        repo.saveSummary(session, DAVID, "   ");
        assertThat(repo.loadSummary(session, DAVID)).as("blank reads as absent").isEmpty();

        repo.delete(session, DAVID);
        assertThat(repo.findBySession(session, DAVID)).isEmpty();
        assertThat(repo.loadSummary(session, DAVID)).isEmpty();
    }

    @Test
    void anotherUserNeitherSeesNorOverwritesTheSessionsMemory() {
        repo.save(session, DAVID, memory("mine"));

        assertThat(repo.findBySession(session, EVE)).isEmpty();
        repo.save(session, EVE, memory("hers"));
        repo.saveSummary(session, EVE, "hers");
        repo.delete(session, EVE);

        assertThat(repo.findBySession(session, DAVID)).contains(memory("mine"));
        assertThat(repo.loadSummary(session, DAVID)).isEmpty();
    }
}
