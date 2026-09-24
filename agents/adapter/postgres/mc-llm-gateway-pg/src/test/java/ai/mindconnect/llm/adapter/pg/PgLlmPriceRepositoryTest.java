package ai.mindconnect.llm.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepositoryContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The repository contract against a real Postgres; skipped when none answers on 5433
 * ({@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}).
 */
class PgLlmPriceRepositoryTest extends LlmPriceRepositoryContract {

    private static final Namespace NS = new Namespace("test");

    private Sql sql;

    @BeforeEach
    void setUp() {
        sql = Sql.of(requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_llm_price");
    }

    @Override
    protected LlmPriceRepository repository() {
        return new PgLlmPriceRepository(sql, NS).initSchema();
    }

    @Test
    void aRepositoryBoundToAnotherNamespaceSeesNothing() {
        LlmPriceRepository mine = repository();
        LlmPriceRepository other = new PgLlmPriceRepository(sql, new Namespace("other")).initSchema();
        LlmPrice price = price("claude", "2026-01-01", null, "3", "15", null);
        mine.save(price);

        assertThat(other.findById(price.id())).isEmpty();
        assertThat(other.findByConfigName("claude")).isEmpty();
        assertThat(other.findAll()).isEmpty();
        other.deleteById(price.id());

        assertThat(mine.findById(price.id())).isPresent();
    }

    @Test
    void theNamespaceIsAColumnSoTheNamespacePurgeFindsTheTable() {
        repository();
        Integer columns = sql.queryOne(
                "SELECT count(*)::int AS n FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = 'mc_llm_price' AND column_name = 'namespace'",
                row -> row.integer("n")).orElse(0);
        assertThat(columns).isEqualTo(1);
    }

    @Test
    void theModelIsARequiredColumnBesideTheConfigNameAndIndexedWithIt() {
        repository();
        String nullable = sql.queryOne(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = 'mc_llm_price' AND column_name = 'model'",
                row -> row.string("is_nullable")).orElse(null);
        assertThat(nullable).isEqualTo("NO");
        String index = sql.queryOne("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                        + "AND indexname = 'mc_llm_price_namespace_config_name_model_idx'",
                row -> row.string("indexdef")).orElse("");
        assertThat(index).contains("(namespace, config_name, model)");

        LlmPrice price = price("openai-default", "gpt-5.4-mini", "2026-01-01", null, "0.4", "1.6", null);
        repository().save(price);
        assertThat(sql.queryOne("SELECT model FROM mc_llm_price WHERE id = ?", row -> row.string("model"),
                price.id().value())).contains("gpt-5.4-mini");
        assertThat(sql.queryOne("SELECT doc->>'model' AS m FROM mc_llm_price WHERE id = ?",
                row -> row.string("m"), price.id().value())).contains("gpt-5.4-mini");
    }

    @Test
    void aTableFromBeforeTheModelGainsTheColumnAndItsRowsPriceAnyModel() {
        sql.execute("""
                CREATE TABLE mc_llm_price (
                    namespace TEXT NOT NULL,
                    id TEXT NOT NULL,
                    config_name TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    doc JSONB NOT NULL,
                    PRIMARY KEY (namespace, id)
                )""");
        sql.update("INSERT INTO mc_llm_price (namespace, id, config_name, doc) VALUES (?, ?, ?, ?::jsonb)",
                "test", "old-price", "claude", "{\"id\":\"old-price\",\"configName\":\"claude\","
                        + "\"validFrom\":\"2026-01-01\",\"validTo\":null,\"currency\":\"USD\","
                        + "\"inputPerMillion\":3,\"outputPerMillion\":15,\"cachedInputPerMillion\":null}");

        LlmPriceRepository repo = repository();

        LlmPrice old = repo.findById(ai.mindconnect.llm.domain.LlmPriceId.of("old-price")).orElseThrow();
        assertThat(old.model()).isNull();
        assertThat(old.anyModel()).isTrue();
        assertThat(repo.priceAt("claude", "claude-sonnet-4-6", Instant.parse("2026-09-24T00:00:00Z")))
                .contains(old);
        LlmPrice named = price("claude", "claude-sonnet-4-6", "2026-01-01", null, "3", "15", null);
        repo.save(named);
        assertThat(repo.priceAt("claude", "claude-sonnet-4-6", Instant.parse("2026-09-24T00:00:00Z")))
                .contains(named);
    }

    private static DataSource requirePostgres() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres"));
        ds.setUser(System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test"));
        try (Connection c = ds.getConnection()) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable — skipping");
        }
        return ds;
    }
}
