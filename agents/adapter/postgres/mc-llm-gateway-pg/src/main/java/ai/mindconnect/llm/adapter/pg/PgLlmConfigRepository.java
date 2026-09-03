package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LlmConfigRepository} on Postgres. Each config is one row of
 * {@code mc_llm_config}: the JSON document, and the name beside it because
 * that is what the gateway looks configs up by.
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

    public PgLlmConfigRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgLlmConfigRepository(Sql sql) {
        this.configs = DocumentTable.of(LlmConfig.class)
                .table("mc_llm_config")
                .id("id", "UUID", LlmConfig::id)
                .requiredColumn("name", "TEXT", LlmConfig::name)
                .index("name")
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
    public Optional<LlmConfig> findById(UUID id) {
        return configs.findById(id);
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return configs.findOne("WHERE name = ? ORDER BY updated_at LIMIT 1", name);
    }

    @Override
    public List<LlmConfig> findAll() {
        return configs.find("ORDER BY name");
    }

    @Override
    public void deleteById(UUID id) {
        configs.deleteById(id);
    }
}
