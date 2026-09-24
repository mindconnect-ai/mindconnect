package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.domain.LlmPrices;
import ai.mindconnect.llm.port.out.LlmPriceRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link LlmPriceRepository} on Postgres. Each price period is one row of
 * {@code mc_llm_price}, keyed by {@code (namespace, id)}: the JSON document,
 * and the config name beside it because that is what a lookup asks by. The
 * namespace column is what the namespace purge deletes by.
 */
public final class PgLlmPriceRepository implements LlmPriceRepository {

    private final DocumentTable<LlmPrice> prices;
    private final Namespace namespace;

    public PgLlmPriceRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgLlmPriceRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.prices = DocumentTable.of(LlmPrice.class)
                .table("mc_llm_price")
                .partitionKey("namespace", "TEXT", p -> namespace.value())
                .id("id", "TEXT", p -> p.id().value())
                .requiredColumn("config_name", "TEXT", LlmPrice::configName)
                .index("namespace", "config_name")
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
        return prices.findById(namespace.value(), id.value());
    }

    @Override
    public List<LlmPrice> findByConfigName(String configName) {
        return prices.find("WHERE namespace = ? AND config_name = ?", namespace.value(), configName).stream()
                .sorted(LlmPrices.CHRONOLOGICAL).toList();
    }

    @Override
    public List<LlmPrice> findAll() {
        return prices.find("WHERE namespace = ?", namespace.value()).stream()
                .sorted(LlmPrices.CHRONOLOGICAL).toList();
    }

    @Override
    public void deleteById(LlmPriceId id) {
        prices.deleteById(namespace.value(), id.value());
    }
}
