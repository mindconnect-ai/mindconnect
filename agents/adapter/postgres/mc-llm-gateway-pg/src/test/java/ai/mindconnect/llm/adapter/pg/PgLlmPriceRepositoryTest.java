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
