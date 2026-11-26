package ai.mindconnect.agent.migration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import ai.mindconnect.agent.scope.ScopeSupplier;

/** The log of an installation that keeps nothing past the process, and of tests. */
public final class InMemoryMigrationLog implements MigrationLog {

    private final Map<String, List<AppliedMigration>> byNamespace = new ConcurrentHashMap<>();
    private final ScopeSupplier scope;

    public InMemoryMigrationLog(ScopeSupplier scope) {
        this.scope = scope;
    }

    @Override
    public List<AppliedMigration> applied() {
        return List.copyOf(byNamespace.getOrDefault(scope.namespace().value(), List.of()));
    }

    @Override
    public void record(AppliedMigration migration) {
        byNamespace.computeIfAbsent(scope.namespace().value(), namespace -> new CopyOnWriteArrayList<>())
                .add(migration);
    }
}
