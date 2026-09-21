package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.Preferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgPreferenceRepositoryTest {

    private static final Instant AT = Instant.parse("2026-09-21T10:00:00Z");

    private PgPreferenceRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_user_preference");
        repo = new PgPreferenceRepository(sql).initSchema();
    }

    @Test
    void one_row_per_user_and_scope_replaced_on_save() {
        UserId alice = UserId.of("alice/with-a-slash");
        repo.save(new Preferences(alice, "office.email", Map.of("folder", "INBOX"), AT));
        Preferences archive = new Preferences(alice, "office.email",
                Map.of("folder", "Archive", "mailbox", "email.work"), AT.plusSeconds(60));
        repo.save(archive);
        repo.save(new Preferences(alice, "office.todos", Map.of("list", "inbox"), AT));
        repo.save(new Preferences(UserId.of("bob"), "office.email", Map.of("folder", "Sent"), AT));

        assertThat(repo.find(alice, "office.email")).contains(archive);
        assertThat(repo.findByUser(alice)).extracting(Preferences::scope)
                .containsExactly("office.email", "office.todos");

        repo.delete(alice, "office.email");
        assertThat(repo.find(alice, "office.email")).isEmpty();
        assertThat(repo.find(UserId.of("bob"), "office.email")).isPresent();
    }
}
