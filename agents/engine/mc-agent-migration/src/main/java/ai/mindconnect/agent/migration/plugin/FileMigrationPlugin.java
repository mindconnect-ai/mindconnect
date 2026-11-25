package ai.mindconnect.agent.migration.plugin;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.migration.FileMigrationLog;
import ai.mindconnect.agent.migration.MigrationLog;
import ai.mindconnect.agent.migration.earlier.EarlierRuntimeData;
import ai.mindconnect.agent.migration.earlier.FileEarlierRuntimeData;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** The migration log and the earlier runtime's data of an installation on files, under its data directory. */
public final class FileMigrationPlugin implements Plugin {

    private final Path dataDir;

    public FileMigrationPlugin(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public String name() {
        return "migrations-file";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(MigrationLog.class, () -> new FileMigrationLog(dataDir, registry.get(ObjectMapper.class),
                registry.get(ScopeSupplier.class)));
        registry.provide(EarlierRuntimeData.class, () -> new FileEarlierRuntimeData(dataDir,
                registry.get(ObjectMapper.class), registry.get(ScopeSupplier.class)));
    }
}
