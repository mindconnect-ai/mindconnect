package ai.mindconnect.extension.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.extension.domain.InstalledSeed;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Which bundled records a namespace got once, on Postgres: one row of
 * {@code mc_installed_seed} per record, keyed by {@code (namespace, seed)}
 * where {@code seed} is {@code kind:name}. Rows are only ever inserted — a
 * key that is there keeps its first row. Bound to one namespace: every row
 * it writes carries it, every statement matches it; the {@code namespace}
 * column is what the namespace purge finds the rows by.
 */
public final class PgInstalledSeedRepository implements InstalledSeedRepository {

    private final DocumentTable<InstalledSeed> seeds;
    private final Namespace namespace;

    public PgInstalledSeedRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgInstalledSeedRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.seeds = DocumentTable.of(InstalledSeed.class)
                .table("mc_installed_seed")
                .partitionKey("namespace", "TEXT", s -> namespace.value())
                .id("seed", "TEXT", InstalledSeed::key)
                .requiredColumn("source", "TEXT", InstalledSeed::source)
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgInstalledSeedRepository initSchema() {
        seeds.createSchema();
        return this;
    }

    @Override
    public List<InstalledSeed> all() {
        return seeds.find("WHERE namespace = ? ORDER BY seed", namespace.value());
    }

    @Override
    public void record(Collection<InstalledSeed> installed) {
        installed.forEach(seeds::insert);
    }
}
