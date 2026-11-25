package ai.mindconnect.agent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;

class MigrationRunnerTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    private final List<String> ran = new ArrayList<>();
    private final MigrationLog log = new InMemoryMigrationLog(ScopeSupplier.fixed(new Namespace("test")));

    private Migration migration(String id, String version) {
        return new Migration() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public String migrate() {
                ran.add(id);
                return "did " + id;
            }
        };
    }

    @Test
    void migrations_run_by_version_and_each_once() {
        MigrationRunner runner = new MigrationRunner(List.of(migration("c", "0.10.0"), migration("b", "0.9.1"),
                migration("a", "0.9.1"), migration("d", "0.9")), log, NOW);

        List<AppliedMigration> first = runner.run();
        List<AppliedMigration> second = runner.run();

        assertThat(ran).containsExactly("d", "a", "b", "c");
        assertThat(first).extracting(AppliedMigration::report).containsExactly("did d", "did a", "did b", "did c");
        assertThat(second).isEmpty();
        assertThat(log.applied()).extracting(AppliedMigration::appliedAt).containsOnly(NOW.instant());
        assertThat(runner.dataVersion()).contains("0.10.0");
    }

    @Test
    void a_migration_added_later_runs_on_its_own() {
        new MigrationRunner(List.of(migration("a", "0.9.0")), log, NOW).run();

        new MigrationRunner(List.of(migration("a", "0.9.0"), migration("b", "0.9.1")), log, NOW).run();

        assertThat(ran).containsExactly("a", "b");
    }

    @Test
    void a_migration_that_fails_is_not_logged_and_stops_the_ones_after_it() {
        Migration broken = new Migration() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public String version() {
                return "0.9.1";
            }

            @Override
            public String migrate() {
                throw new IllegalStateException("half done");
            }
        };
        MigrationRunner runner = new MigrationRunner(List.of(migration("a", "0.9.0"), broken,
                migration("c", "0.9.2")), log, NOW);

        assertThatThrownBy(runner::run).hasMessage("half done");

        assertThat(ran).containsExactly("a");
        assertThat(log.applied()).extracting(AppliedMigration::id).containsExactly("a");
    }

    @Test
    void two_migrations_of_one_id_are_refused() {
        assertThatThrownBy(() -> new MigrationRunner(List.of(migration("a", "0.9.0"), migration("a", "0.9.1")),
                log, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void without_any_migration_the_data_has_no_version() {
        assertThat(new MigrationRunner(List.of(), log, NOW).dataVersion()).isEmpty();
    }
}
