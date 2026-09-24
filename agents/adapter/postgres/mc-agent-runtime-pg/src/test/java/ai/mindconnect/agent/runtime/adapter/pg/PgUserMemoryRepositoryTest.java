package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PgUserMemoryRepositoryTest {

    private static final UserId ALICE = UserId.of("alice@example.com");
    private static final UserId BOB = UserId.of("bob");

    private Sql sql;
    private PgUserMemoryRepository repo;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_user_memory");
        repo = new PgUserMemoryRepository(sql, new Namespace("acme")).initSchema();
    }

    @Test
    void anEntryIsKeyedByUserAndNameAndReplacedOnSave() {
        MemoryEntry first = entry(ALICE, "role", "Buyer");
        repo.save(first);
        MemoryEntry second = entry(ALICE, "role", "Head of purchasing");
        repo.save(second);

        assertThat(repo.find(ALICE, "role")).contains(second);
        assertThat(repo.findByUser(ALICE)).containsExactly(second);
    }

    @Test
    void theSameNameBelongsToEachUserSeparately() {
        repo.save(entry(ALICE, "role", "Buyer"));
        repo.save(entry(BOB, "role", "Engineer"));

        assertThat(repo.findByUser(BOB)).extracting(MemoryEntry::description).containsExactly("Engineer");
        assertThat(repo.delete(BOB, "role")).isTrue();
        assertThat(repo.find(ALICE, "role")).isPresent();
        assertThat(repo.delete(BOB, "role")).isFalse();
    }

    @Test
    void anotherNamespaceSeesNothing() {
        repo.save(entry(ALICE, "role", "Buyer"));

        PgUserMemoryRepository other = new PgUserMemoryRepository(sql, new Namespace("other")).initSchema();
        assertThat(other.findByUser(ALICE)).isEmpty();
        assertThat(other.find(ALICE, "role")).isEmpty();
    }

    private static MemoryEntry entry(UserId user, String name, String description) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new MemoryEntry(user, name, MemoryType.USER, description, "content",
                SessionId.of("s1"), now, now);
    }
}
