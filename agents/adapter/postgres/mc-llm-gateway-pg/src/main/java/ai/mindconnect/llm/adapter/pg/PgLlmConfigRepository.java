package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LlmConfigRepository} on Postgres. Each config is one row of
 * {@code mc_llm_config}, keyed by {@code (namespace, id)}: the JSON document,
 * and the name beside it because that is what the gateway looks configs up by.
 * The repository is bound to one namespace and every statement matches it.
 *
 * <p>Two configs with the same name are not refused — the file store never
 * did either, and {@link #findByName} returns the first, as it always has.
 *
 * <p>The API key is stored as it arrives. Wrap this repository in
 * {@code EncryptingLlmConfigRepository} exactly as the file store is wrapped;
 * encryption is that decorator's job, not the store's.
 */
public final class PgLlmConfigRepository implements LlmConfigRepository {

    private final DocumentTable<LlmConfig> configs;
    private final Namespace namespace;

    public PgLlmConfigRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgLlmConfigRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.configs = DocumentTable.of(LlmConfig.class)
                .table("mc_llm_config")
                .partitionKey("namespace", "TEXT", c -> namespace.value())
                .id("id", "TEXT", c -> c.id().value())
                .requiredColumn("name", "TEXT", LlmConfig::name)
                .index("namespace", "name")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgLlmConfigRepository initSchema() {
        configs.createSchema();
        return this;
    }

    @Override
    public void save(LlmConfig config) {
        configs.save(config);
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        return configs.findById(namespace.value(), id.value());
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return configs.findOne("WHERE namespace = ? AND name = ? ORDER BY updated_at LIMIT 1", namespace.value(), name);
    }

    @Override
    public List<LlmConfig> findAll() {
        return configs.find("WHERE namespace = ? ORDER BY name", namespace.value());
    }

    @Override
    public void deleteById(LlmConfigId id) {
        configs.deleteById(namespace.value(), id.value());
    }
}
