package ai.mindconnect.agent.migration.pg;

import ai.mindconnect.agent.migration.MigrationLog;
import ai.mindconnect.agent.migration.earlier.EarlierRuntimeData;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** The migration log and the earlier runtime's data of an installation in Postgres, on the runtime's {@link Sql}. */
public final class PgMigrationPlugin implements Plugin {

    @Override
    public String name() {
        return "migrations-postgres";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(MigrationLog.class, () -> new PgMigrationLog(registry.get(Sql.class),
                registry.get(ScopeSupplier.class)).initSchema());
        registry.provide(EarlierRuntimeData.class, () -> new PgEarlierRuntimeData(registry.get(Sql.class),
                registry.get(ScopeSupplier.class)));
    }
}
