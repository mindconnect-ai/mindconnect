package ai.mindconnect.agent.migration;

import java.util.List;

/**
 * The migrations that ran in the namespace the current call works in, kept
 * with that namespace's data: a database restored from a dump knows what was
 * migrated in it, wherever it runs.
 */
public interface MigrationLog {

    List<AppliedMigration> applied();

    void record(AppliedMigration migration);
}
