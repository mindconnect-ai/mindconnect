package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
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
 * Every statement matches the namespace the scope names for the current call.
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
    private final ScopeSupplier scope;

    public PgLlmConfigRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgLlmConfigRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.configs = DocumentTable.of(LlmConfig.class)
                .table("mc_llm_config")
                .partitionKey("namespace", "TEXT", c -> scope.namespace().value())
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

    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    @Override
    public void save(LlmConfig config) {
        configs.compute(scope.namespace().value(), config.id().value(), current ->
                config.withVersion(ai.mindconnect.common.Versions.next(
                        current.map(LlmConfig::version).orElse(null), config.version(),
                        "LlmConfig", config.id().value())));
    }

    @Override
    public Optional<LlmConfig> findById(LlmConfigId id) {
        return configs.findById(scope.namespace().value(), id.value());
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return configs.findOne("WHERE namespace = ? AND name = ? ORDER BY updated_at LIMIT 1", scope.namespace().value(), name);
    }

    @Override
    public List<LlmConfig> findAll() {
        return configs.find("WHERE namespace = ? ORDER BY name", scope.namespace().value());
    }

    @Override
    public void deleteById(LlmConfigId id) {
        configs.deleteById(scope.namespace().value(), id.value());
    }
}
