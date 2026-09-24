package ai.mindconnect.adminui;

import ai.mindconnect.adminui.service.MigrationService;
import ai.mindconnect.adminui.service.NamespaceSeeding;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.StartupScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the start-up namespace ({@code mindconnect.namespace}, {@code local}
 * unless configured) at start, so that it has its content before anybody
 * asks: the LLM configs, agents, skills and workflows every jar ships under
 * {@code initial-data/} — the app's own and those of every module or
 * extension on the classpath.
 *
 * <p>It is the same seeding every other namespace gets on its first use
 * ({@link NamespaceSeeding}): a record the namespace never had is installed,
 * one that is there is never touched, one an admin deleted stays deleted, and
 * an extension's content waits until the extension is on here. What differs
 * from the stored version is reviewed on Install → Migrations; this only says
 * how many such records there are.
 */
@Component
public class InitialDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialDataLoader.class);

    private final NamespaceSeeding seeding;
    private final MigrationService migrations;
    private final ScopeSupplier scope;
    private final Namespace startupNamespace;

    public InitialDataLoader(NamespaceSeeding seeding, MigrationService migrations,
                             ObjectProvider<ScopeSupplier> scope,
                             @Value("${mindconnect.namespace:local}") String startupNamespace) {
        this.seeding = seeding;
        this.migrations = migrations;
        this.scope = scope.getIfAvailable();
        this.startupNamespace = new Namespace(startupNamespace);
    }

    /** Seeds run in the default namespace: the main thread binds no scope of its own. */
    @Override
    public void run(ApplicationArguments args) {
        StartupScope.run(scope, startupNamespace, this::load);
    }

    /** Seeds the bound namespace, then names the bundled records that differ from the stored ones. */
    public void load() {
        seeding.ensure(startupNamespace);
        try {
            List<String> changed = migrations.pending().stream()
                    .filter(pending -> pending.status() == MigrationService.Status.CHANGED)
                    .map(MigrationService.PendingMigration::id)
                    .toList();
            if (!changed.isEmpty()) {
                log.info("{} bundled record(s) differ from the stored version in namespace '{}' — review them "
                        + "on Install → Migrations: {}", changed.size(), startupNamespace.value(), changed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not compare the bundled records with the stored ones: {}", e.getMessage());
        }
    }
}
