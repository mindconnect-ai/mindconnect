package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ExtensionActivationRepository} on Postgres: one row of
 * {@code mc_extension_activation} per decision, keyed by
 * {@code (namespace, extension_id)}. Every row it writes carries the namespace
 * the scope names for the current call, every statement matches it. The {@code namespace}
 * column is what the namespace purge finds the rows by.
 */
public final class PgExtensionActivationRepository implements ExtensionActivationRepository {

    private final DocumentTable<ExtensionActivation> activations;
    private final ScopeSupplier scope;

    public PgExtensionActivationRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgExtensionActivationRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.activations = DocumentTable.of(ExtensionActivation.class)
                .table("mc_extension_activation")
                .partitionKey("namespace", "TEXT", a -> scope.namespace().value())
                .id("extension_id", "TEXT", a -> a.extensionId().value())
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgExtensionActivationRepository initSchema() {
        activations.createSchema();
        return this;
    }

    @Override
    public Optional<ExtensionActivation> find(ExtensionId id) {
        return activations.findById(scope.namespace().value(), id.value());
    }

    @Override
    public List<ExtensionActivation> all() {
        return activations.find("WHERE namespace = ? ORDER BY extension_id", scope.namespace().value());
    }

    @Override
    public void save(ExtensionActivation activation) {
        activations.save(activation);
    }

    @Override
    public void delete(ExtensionId id) {
        activations.deleteById(scope.namespace().value(), id.value());
    }
}
