package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link VectorStoreRegistry} on Postgres: one row of
 * {@code mc_vector_store_template} per template and one of
 * {@code mc_vector_store_instance} per instance, keyed by
 * {@code (namespace, id)} where the id is the name's {@link VectorStoreRegistry#key key},
 * and one row of {@value #MEMBERS} per entity a store lists
 * — so a name finds the same record it found on files. The name stands beside
 * the document, and an instance's scope too, for {@link #instances(VectorStoreInstance.Scope, String)}.
 *
 * <p>Every row it writes carries the namespace the scope names for the current
 * call, and every statement it runs matches it, so the stores of another
 * namespace in the same tables are invisible here.
 */
public final class PgVectorStoreRegistry implements VectorStoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreRegistry.class);

    static final String MEMBERS = "mc_vector_store_member";

    private final DocumentTable<VectorStoreTemplate> templates;
    private final DocumentTable<VectorStoreInstance> instances;
    private final DocumentTable<IndexDefinition> indexes;
    private final Sql sql;
    private final ScopeSupplier scope;

    public PgVectorStoreRegistry(Sql sql, ScopeSupplier scope) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.templates = DocumentTable.of(VectorStoreTemplate.class)
                .table("mc_vector_store_template")
                .partitionKey("namespace", "TEXT", t -> scope.namespace().value())
                .id("id", "TEXT", t -> VectorStoreRegistry.key(t.name()))
                .requiredColumn("name", "TEXT", VectorStoreTemplate::name)
                .build(sql);
        this.instances = DocumentTable.of(VectorStoreInstance.class)
                .table("mc_vector_store_instance")
                .partitionKey("namespace", "TEXT", i -> scope.namespace().value())
                .id("id", "TEXT", i -> VectorStoreRegistry.key(i.name()))
                .requiredColumn("name", "TEXT", VectorStoreInstance::name)
                .requiredColumn("scope", "TEXT", i -> i.scope().name())
                .column("scope_ref", "TEXT", VectorStoreInstance::scopeRef)
                .index("namespace", "scope", "scope_ref")
                .build(sql);
        this.indexes = DocumentTable.of(IndexDefinition.class)
                .table("mc_vector_store_index")
                .partitionKey("namespace", "TEXT", i -> scope.namespace().value())
                .id("id", "TEXT", i -> i.name())
                .build(sql);
    }

    public PgVectorStoreRegistry initSchema() {
        templates.createSchema();
        instances.createSchema();
        indexes.createSchema();
        sql.execute("CREATE TABLE IF NOT EXISTS " + MEMBERS + " ("
                + " namespace text NOT NULL,"
                + " store_id text NOT NULL,"
                + " entity_type text NOT NULL,"
                + " source text NOT NULL,"
                + " container text NOT NULL,"
                + " entity_id text NOT NULL,"
                + " added_at timestamptz NOT NULL DEFAULT clock_timestamp(),"
                + " PRIMARY KEY (namespace, store_id, entity_type, source, container, entity_id));"
                + " CREATE INDEX IF NOT EXISTS " + MEMBERS + "_entity_idx ON " + MEMBERS
                + " (namespace, entity_type, source, container, entity_id)");
        return this;
    }

    /**
     * Copies {@code source}'s templates and instances here — once: only while
     * this namespace has neither in the database. The source is left as it was.
     *
     * @return how many records were copied
     */
    public int importFrom(VectorStoreRegistry source) {
        if (templates.exists("WHERE namespace = ?", scope.namespace().value())
                || instances.exists("WHERE namespace = ?", scope.namespace().value())) {
            return 0;
        }
        int copied = 0;
        for (VectorStoreTemplate template : source.templates()) {
            if (templates.insert(template)) copied++;
        }
        for (VectorStoreInstance instance : source.instances()) {
            if (instances.insert(instance)) copied++;
            source.members(instance.name()).forEach(ref -> addMember(instance.name(), ref));
        }
        for (IndexDefinition index : source.indexes()) {
            if (indexes.insert(index)) copied++;
        }
        if (copied > 0) {
            log.info("Imported {} vector-store template(s) and instance(s) of namespace '{}' into Postgres",
                    copied, scope.namespace().value());
        }
        return copied;
    }

    // ── templates ──────────────────────────────────────────────────────────

    @Override
    public List<VectorStoreTemplate> templates() {
        return templates.find("WHERE namespace = ? ORDER BY id", scope.namespace().value());
    }

    @Override
    public Optional<VectorStoreTemplate> template(String name) {
        return templates.findById(scope.namespace().value(), VectorStoreRegistry.key(name));
    }

    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    @Override
    public VectorStoreTemplate saveTemplate(VectorStoreTemplate template) {
        return templates.compute(scope.namespace().value(), VectorStoreRegistry.key(template.name()), current ->
                template.withVersion(ai.mindconnect.common.Versions.next(
                        current.map(VectorStoreTemplate::version).orElse(null), template.version(),
                        "VectorStoreTemplate", template.name())));
    }

    @Override
    public void deleteTemplate(String name) {
        templates.deleteById(scope.namespace().value(), VectorStoreRegistry.key(name));
    }

    // ── instances ──────────────────────────────────────────────────────────

    @Override
    public List<VectorStoreInstance> instances() {
        return instances.find("WHERE namespace = ? ORDER BY id", scope.namespace().value());
    }

    @Override
    public Optional<VectorStoreInstance> instance(String name) {
        return instances.findById(scope.namespace().value(), VectorStoreRegistry.key(name));
    }

    /** The existing row, read {@code FOR UPDATE}, wins; without one the candidate is inserted. */
    @Override
    public VectorStoreInstance registerInstance(VectorStoreInstance candidate) {
        VectorStoreInstance stored = instances.compute(scope.namespace().value(), VectorStoreRegistry.key(candidate.name()),
                current -> current.orElse(candidate));
        if (stored == candidate) {
            log.info("Registered vector store '{}' from template '{}' (scope {})",
                    candidate.name(), candidate.templateName(), candidate.scope());
        }
        return stored;
    }

    @Override
    public void saveInstance(VectorStoreInstance instance) {
        instances.save(instance);
    }

    @Override
    public List<VectorStoreInstance> instances(VectorStoreInstance.Scope scope, String scopeRef) {
        return scopeRef == null
                ? instances.find("WHERE namespace = ? AND scope = ? ORDER BY id", this.scope.namespace().value(), scope)
                : instances.find("WHERE namespace = ? AND scope = ? AND scope_ref = ? ORDER BY id",
                        this.scope.namespace().value(), scope, scopeRef);
    }

    @Override
    public void deleteInstance(String name) {
        instances.deleteById(scope.namespace().value(), VectorStoreRegistry.key(name));
        sql.update("DELETE FROM " + MEMBERS + " WHERE namespace = ? AND store_id = ?",
                scope.namespace().value(), VectorStoreRegistry.key(name));
    }

    // ── indexes ────────────────────────────────────────────────────────────

    @Override
    public List<IndexDefinition> indexes() {
        return indexes.find("WHERE namespace = ? ORDER BY id", scope.namespace().value());
    }

    @Override
    public Optional<IndexDefinition> index(String name) {
        return indexes.findById(scope.namespace().value(), name);
    }

    @Override
    public void saveIndex(IndexDefinition index) {
        indexes.save(index);
    }

    @Override
    public void deleteIndex(String name) {
        indexes.deleteById(scope.namespace().value(), name);
    }

    // ── members ────────────────────────────────────────────────────────────

    @Override
    public List<EntityRef> members(String store) {
        return sql.query("SELECT entity_type, source, container, entity_id FROM " + MEMBERS
                        + " WHERE namespace = ? AND store_id = ? ORDER BY added_at, entity_type, source, container, entity_id",
                row -> new EntityRef(EntityType.of(row.string("entity_type")), row.string("source"),
                        row.string("container"), row.string("entity_id")),
                scope.namespace().value(), VectorStoreRegistry.key(store));
    }

    @Override
    public void addMember(String store, EntityRef ref) {
        sql.update("INSERT INTO " + MEMBERS + " (namespace, store_id, entity_type, source, container, entity_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                scope.namespace().value(), VectorStoreRegistry.key(store), ref.type().value(), ref.source(),
                ref.container(), ref.id());
    }

    @Override
    public void removeMember(String store, EntityRef ref) {
        sql.update("DELETE FROM " + MEMBERS + " WHERE namespace = ? AND store_id = ?"
                        + " AND entity_type = ? AND source = ? AND container = ? AND entity_id = ?",
                scope.namespace().value(), VectorStoreRegistry.key(store), ref.type().value(), ref.source(),
                ref.container(), ref.id());
    }

    @Override
    public List<String> storesListing(EntityRef ref) {
        return sql.query("SELECT store_id FROM " + MEMBERS + " WHERE namespace = ?"
                        + " AND entity_type = ? AND source = ? AND container = ? AND entity_id = ? ORDER BY store_id",
                row -> row.string("store_id"),
                scope.namespace().value(), ref.type().value(), ref.source(), ref.container(), ref.id());
    }
}
