package ai.mindconnect.jdbc;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Postgres the integration tests run against, or a skipped test. Same
 * convention as the task-queue and pgvector suites:
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 */
final class TestDb {

    static final String URL = System.getenv().getOrDefault(
            "MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");

    private TestDb() {
    }

    static DataSource requirePostgres() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test"));
        try (Connection c = ds.getConnection()) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable at " + URL + " — skipping");
        }
        return ds;
    }
}
