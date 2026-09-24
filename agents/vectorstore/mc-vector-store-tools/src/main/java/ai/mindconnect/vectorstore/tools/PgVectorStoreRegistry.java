package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link VectorStoreRegistry} on Postgres: one row of
 * {@code mc_vector_store_template} per template and one of
 * {@code mc_vector_store_instance} per instance, keyed by
 * {@code (namespace, id)} where the id is the name's {@link VectorStoreRegistry#key key}
 * — so a name finds the same record it found on files. The name stands beside
 * the document, and an instance's scope too, for {@link #instances(VectorStoreInstance.Scope, String)}.
 *
 * <p>The registry is bound to one namespace: every row it writes carries it,
 * and every statement it runs matches it, so the stores of another namespace
 * in the same tables are invisible here.
 */
public final class PgVectorStoreRegistry implements VectorStoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreRegistry.class);

    private final DocumentTable<VectorStoreTemplate> templates;
    private final DocumentTable<VectorStoreInstance> instances;
    private final Namespace namespace;

    public PgVectorStoreRegistry(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.templates = DocumentTable.of(VectorStoreTemplate.class)
                .table("mc_vector_store_template")
                .partitionKey("namespace", "TEXT", t -> namespace.value())
                .id("id", "TEXT", t -> VectorStoreRegistry.key(t.name()))
                .requiredColumn("name", "TEXT", VectorStoreTemplate::name)
                .build(sql);
        this.instances = DocumentTable.of(VectorStoreInstance.class)
                .table("mc_vector_store_instance")
                .partitionKey("namespace", "TEXT", i -> namespace.value())
                .id("id", "TEXT", i -> VectorStoreRegistry.key(i.name()))
                .requiredColumn("name", "TEXT", VectorStoreInstance::name)
                .requiredColumn("scope", "TEXT", i -> i.scope().name())
                .column("scope_ref", "TEXT", VectorStoreInstance::scopeRef)
                .index("namespace", "scope", "scope_ref")
                .build(sql);
    }

    public PgVectorStoreRegistry initSchema() {
        templates.createSchema();
        instances.createSchema();
        return this;
    }

    /**
     * Copies {@code source}'s templates and instances here — once: only while
     * this namespace has neither in the database. The source is left as it was.
     *
     * @return how many records were copied
     */
    public int importFrom(VectorStoreRegistry source) {
        if (templates.exists("WHERE namespace = ?", namespace.value())
                || instances.exists("WHERE namespace = ?", namespace.value())) {
            return 0;
        }
        int copied = 0;
        for (VectorStoreTemplate template : source.templates()) {
            if (templates.insert(template)) copied++;
        }
        for (VectorStoreInstance instance : source.instances()) {
            if (instances.insert(instance)) copied++;
        }
        if (copied > 0) {
            log.info("Imported {} vector-store template(s) and instance(s) of namespace '{}' into Postgres",
                    copied, namespace.value());
        }
        return copied;
    }

    // ── templates ──────────────────────────────────────────────────────────

    @Override
    public List<VectorStoreTemplate> templates() {
        return templates.find("WHERE namespace = ? ORDER BY id", namespace.value());
    }

    @Override
    public Optional<VectorStoreTemplate> template(String name) {
        return templates.findById(namespace.value(), VectorStoreRegistry.key(name));
    }

    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    @Override
    public VectorStoreTemplate saveTemplate(VectorStoreTemplate template) {
        return templates.compute(namespace.value(), VectorStoreRegistry.key(template.name()), current ->
                template.withVersion(ai.mindconnect.common.Versions.next(
                        current.map(VectorStoreTemplate::version).orElse(null), template.version(),
                        "VectorStoreTemplate", template.name())));
    }

    @Override
    public void deleteTemplate(String name) {
        templates.deleteById(namespace.value(), VectorStoreRegistry.key(name));
    }

    // ── instances ──────────────────────────────────────────────────────────

    @Override
    public List<VectorStoreInstance> instances() {
        return instances.find("WHERE namespace = ? ORDER BY id", namespace.value());
    }

    @Override
    public Optional<VectorStoreInstance> instance(String name) {
        return instances.findById(namespace.value(), VectorStoreRegistry.key(name));
    }

    /** The existing row, read {@code FOR UPDATE}, wins; without one the candidate is inserted. */
    @Override
    public VectorStoreInstance registerInstance(VectorStoreInstance candidate) {
        VectorStoreInstance stored = instances.compute(namespace.value(), VectorStoreRegistry.key(candidate.name()),
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
                ? instances.find("WHERE namespace = ? AND scope = ? ORDER BY id", namespace.value(), scope)
                : instances.find("WHERE namespace = ? AND scope = ? AND scope_ref = ? ORDER BY id",
                        namespace.value(), scope, scopeRef);
    }

    @Override
    public void deleteInstance(String name) {
        instances.deleteById(namespace.value(), VectorStoreRegistry.key(name));
    }
}
