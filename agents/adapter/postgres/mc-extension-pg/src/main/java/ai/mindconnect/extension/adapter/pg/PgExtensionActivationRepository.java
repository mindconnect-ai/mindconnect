package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.agent.Namespace;
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
 * {@code (namespace, extension_id)}. Bound to one namespace: every row it
 * writes carries it, every statement matches it. The {@code namespace}
 * column is what the namespace purge finds the rows by.
 */
public final class PgExtensionActivationRepository implements ExtensionActivationRepository {

    private final DocumentTable<ExtensionActivation> activations;
    private final Namespace namespace;

    public PgExtensionActivationRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgExtensionActivationRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.activations = DocumentTable.of(ExtensionActivation.class)
                .table("mc_extension_activation")
                .partitionKey("namespace", "TEXT", a -> namespace.value())
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
        return activations.findById(namespace.value(), id.value());
    }

    @Override
    public List<ExtensionActivation> all() {
        return activations.find("WHERE namespace = ? ORDER BY extension_id", namespace.value());
    }

    @Override
    public void save(ExtensionActivation activation) {
        activations.save(activation);
    }

    @Override
    public void delete(ExtensionId id) {
        activations.deleteById(namespace.value(), id.value());
    }
}
