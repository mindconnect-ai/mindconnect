package ai.mindconnect.agent.migration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the migrations a namespace has not had yet, oldest version first, and
 * logs each one that ran. The first that fails stops the run: those after it
 * may build on it.
 */
public final class MigrationRunner {

    /** By version, those of one version by id. */
    public static final Comparator<Migration> ORDER = Comparator
            .comparing(Migration::version, MigrationRunner::compareVersions)
            .thenComparing(Migration::id);

    private final List<Migration> migrations;
    private final MigrationLog log;
    private final Clock clock;

    public MigrationRunner(List<Migration> migrations, MigrationLog log, Clock clock) {
        Set<String> ids = new HashSet<>();
        for (Migration migration : migrations) {
            if (!ids.add(migration.id())) {
                throw new IllegalArgumentException("Two migrations have the id " + migration.id());
            }
        }
        this.migrations = migrations.stream().sorted(ORDER).toList();
        this.log = log;
        this.clock = clock;
    }

    /** Runs what the namespace of the current call has not had; the migrations that ran now. */
    public List<AppliedMigration> run() {
        Set<String> done = log.applied().stream().map(AppliedMigration::id).collect(Collectors.toSet());
        List<AppliedMigration> ran = new ArrayList<>();
        for (Migration migration : migrations) {
            if (done.contains(migration.id())) {
                continue;
            }
            String report = migration.migrate();
            AppliedMigration applied = new AppliedMigration(migration.id(), migration.version(), clock.instant(),
                    report);
            log.record(applied);
            ran.add(applied);
        }
        return ran;
    }

    /** The version the namespace's data is at: the highest of the migrations it had; empty for none. */
    public Optional<String> dataVersion() {
        return log.applied().stream().map(AppliedMigration::version).max(MigrationRunner::compareVersions);
    }

    /**
     * Compares versions such as {@code 0.8.5} and {@code 0.10.0} part by
     * part, numbers as numbers; a version that is the other one with more
     * parts is the later.
     */
    static int compareVersions(String one, String other) {
        String[] left = one.split("[.-]");
        String[] right = other.split("[.-]");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = left[i].matches("\\d+") && right[i].matches("\\d+")
                    ? Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]))
                    : left[i].compareTo(right[i]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
