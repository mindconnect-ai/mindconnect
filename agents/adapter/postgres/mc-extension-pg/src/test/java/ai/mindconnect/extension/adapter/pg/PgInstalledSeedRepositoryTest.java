package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.extension.domain.InstalledSeed;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PgInstalledSeedRepositoryTest {

    private Sql sql;
    private PgInstalledSeedRepository acme;
    private PgInstalledSeedRepository other;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_installed_seed");
        acme = new PgInstalledSeedRepository(sql, new Namespace("acme")).initSchema();
        other = new PgInstalledSeedRepository(sql, new Namespace("other")).initSchema();
    }

    @Test
    void a_seed_is_recorded_per_namespace() {
        acme.record(List.of(InstalledSeed.of("agent", "email-assistant", "office"),
                InstalledSeed.of("llm-config", "claude-default", null)));

        assertThat(acme.keys()).containsExactlyInAnyOrder("agent:email-assistant", "llm-config:claude-default");
        assertThat(acme.all()).filteredOn(seed -> seed.kind().equals("llm-config"))
                .extracting(InstalledSeed::source).containsExactly(InstalledSeed.HOST);
        assertThat(other.all()).isEmpty();
    }

    @Test
    void a_key_recorded_before_keeps_its_first_row() {
        acme.record(List.of(InstalledSeed.of("agent", "email-assistant", "office")));
        acme.record(List.of(InstalledSeed.of("agent", "email-assistant", "other")));

        assertThat(acme.all()).extracting(InstalledSeed::source).containsExactly("office");
    }

    @Test
    void the_rows_carry_the_namespace_the_purge_deletes_by() {
        acme.record(List.of(InstalledSeed.of("agent", "email-assistant", "office")));
        other.record(List.of(InstalledSeed.of("agent", "email-assistant", "office")));

        sql.update("DELETE FROM mc_installed_seed WHERE namespace = ?", "acme");

        assertThat(acme.all()).isEmpty();
        assertThat(new PgInstalledSeedRepository(sql, new Namespace("other")).keys())
                .containsExactly("agent:email-assistant");
    }
}
