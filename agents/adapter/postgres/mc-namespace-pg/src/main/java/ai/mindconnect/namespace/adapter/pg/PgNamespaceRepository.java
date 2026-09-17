package ai.mindconnect.namespace.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link NamespaceRepository} on Postgres: one row of {@code mc_namespace} per
 * namespace, keyed by the id alone — the table is installation-wide, there is
 * no partition column here.
 */
public class PgNamespaceRepository implements NamespaceRepository {

    private final DocumentTable<NamespaceDefinition> namespaces;

    public PgNamespaceRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgNamespaceRepository(Sql sql) {
        this.namespaces = DocumentTable.of(NamespaceDefinition.class)
                .table("mc_namespace")
                .id("id", "TEXT", ns -> ns.id().value())
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgNamespaceRepository initSchema() {
        namespaces.createSchema();
        return this;
    }

    @Override
    public Optional<NamespaceDefinition> findById(Namespace id) {
        return namespaces.findById(id.value());
    }

    @Override
    public List<NamespaceDefinition> findAll() {
        return namespaces.find("ORDER BY id");
    }

    @Override
    public List<NamespaceDefinition> findFor(Actor who) {
        // The lists live in the document; namespaces are few, so filtering here beats a JSONB query nobody else needs.
        return findAll().stream().filter(ns -> ns.isMember(who)).toList();
    }

    @Override
    public void save(NamespaceDefinition namespace) {
        namespaces.save(namespace);
    }

    @Override
    public boolean insert(NamespaceDefinition namespace) {
        return namespaces.insert(namespace);
    }

    @Override
    public boolean deleteById(Namespace id) {
        return namespaces.deleteById(id.value());
    }
}
