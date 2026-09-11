package ai.mindconnect.user.adapter.pg;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A real Postgres, or the test is skipped
 * ({@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}).
 */
class TestDb {

    private TestDb() {}

    static DataSource require() {
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
