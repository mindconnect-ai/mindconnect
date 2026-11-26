package ai.mindconnect.agent.migration.plugin;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.migration.InMemoryMigrationLog;
import ai.mindconnect.agent.migration.Migration;
import ai.mindconnect.agent.migration.MigrationLog;
import ai.mindconnect.agent.migration.MigrationRunner;
import ai.mindconnect.agent.migration.earlier.EarlierRuntimeData;
import ai.mindconnect.agent.migration.earlier.EarlierRuntimeMigration;
import ai.mindconnect.agent.migration.earlier.SessionConversationsMigration;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * The migrations of an installation and the runner that applies them: the
 * ones of this module, and whatever other plugins contribute as
 * {@link Migration}. Where the log and the earlier runtime's data are kept,
 * the place's plugin provides ({@link FileMigrationPlugin}, and its Postgres
 * counterpart); without one, the log lasts as long as the process and there
 * is no earlier data. The host says when the runner runs, and in which
 * namespace.
 */
public final class MigrationPlugin implements Plugin {

    @Override
    public String name() {
        return "migrations";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(MigrationRunner.class, () -> {
            EarlierRuntimeData earlier = registry.find(EarlierRuntimeData.class).orElseGet(EarlierRuntimeData::none);
            ObjectMapper json = registry.get(ObjectMapper.class);
            List<Migration> migrations = new ArrayList<>(registry.all(Migration.class));
            migrations.add(new EarlierRuntimeMigration(earlier, registry.get(AgentRepository.class),
                    registry.get(SessionRepository.class), registry.get(SessionMessageRepository.class),
                    new AgentJson(json), json, Clock.systemUTC()));
            migrations.add(new SessionConversationsMigration(earlier, registry.get(SessionRepository.class),
                    registry.get(SessionMessageRepository.class), json, Clock.systemUTC()));
            return new MigrationRunner(migrations, registry.find(MigrationLog.class)
                    .orElseGet(() -> new InMemoryMigrationLog(registry.get(ScopeSupplier.class))), Clock.systemUTC());
        });
    }
}
