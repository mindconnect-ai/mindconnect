package ai.mindconnect.agent.migration.pg;

import java.util.List;

import ai.mindconnect.agent.migration.AppliedMigration;
import ai.mindconnect.agent.migration.MigrationLog;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

/** The log in Postgres: one row of {@code mc_migration} per namespace and migration that ran there. */
public final class PgMigrationLog implements MigrationLog {

    private final DocumentTable<AppliedMigration> applied;
    private final ScopeSupplier scope;

    public PgMigrationLog(Sql sql, ScopeSupplier scope) {
        this.scope = scope;
        this.applied = DocumentTable.of(AppliedMigration.class)
                .table("mc_migration")
                .partitionKey("namespace", "TEXT", migration -> scope.namespace().value())
                .id("id", "TEXT", AppliedMigration::id)
                .column("version", "TEXT", AppliedMigration::version)
                .column("applied_at", "TIMESTAMPTZ", AppliedMigration::appliedAt)
                .build(sql);
    }

    /** Creates the table where it is missing. */
    public PgMigrationLog initSchema() {
        applied.createSchema();
        return this;
    }

    @Override
    public List<AppliedMigration> applied() {
        return applied.find("WHERE namespace = ? ORDER BY applied_at, id", scope.namespace().value());
    }

    @Override
    public void record(AppliedMigration migration) {
        applied.save(migration);
    }
}
