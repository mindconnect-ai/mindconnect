package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.domain.LlmPriceList;
import ai.mindconnect.llm.port.out.LlmPriceRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LlmPriceRepository} on Postgres. Each price period is one row of
 * {@code mc_llm_price}, keyed by {@code (namespace, id)}: the JSON document,
 * and the config name and model beside it, for queries — and an SQL reader —
 * that go by them. The namespace column is what the namespace purge deletes by.
 *
 * <p>The {@code model} column came after the table: {@link #initSchema} adds
 * it to a table that lacks it ({@code ADD COLUMN IF NOT EXISTS}, nullable
 * there), and a row written before stays readable — its document has no
 * model, so it prices any model of its config (see {@link LlmPrice}).
 */
public final class PgLlmPriceRepository implements LlmPriceRepository {

    private final DocumentTable<LlmPrice> prices;
    private final ScopeSupplier scope;

    public PgLlmPriceRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgLlmPriceRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.prices = DocumentTable.of(LlmPrice.class)
                .table("mc_llm_price")
                .partitionKey("namespace", "TEXT", p -> scope.namespace().value())
                .id("id", "TEXT", p -> p.id().value())
                .requiredColumn("config_name", "TEXT", LlmPrice::configName)
                .requiredColumn("model", "TEXT", LlmPrice::model)
                .index("namespace", "config_name", "model")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgLlmPriceRepository initSchema() {
        prices.createSchema();
        return this;
    }

    @Override
    public void save(LlmPrice price) {
        prices.save(price);
    }

    @Override
    public Optional<LlmPrice> findById(LlmPriceId id) {
        return prices.findById(scope.namespace().value(), id.value());
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return prices.find("WHERE namespace = ? AND config_name = ?", scope.namespace().value(), configName).stream()
                .sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return prices.find("WHERE namespace = ?", scope.namespace().value()).stream()
                .sorted(LlmPriceList.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        prices.deleteById(scope.namespace().value(), id.value());
    }
}
