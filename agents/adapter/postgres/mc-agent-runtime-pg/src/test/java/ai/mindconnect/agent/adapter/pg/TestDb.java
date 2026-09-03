package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.jdbc.Sql;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Postgres the tests run against, or a skipped test:
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 */
final class TestDb {

    private TestDb() {
    }

    static DataSource requirePostgres() {
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

    /** A fresh {@link Sql} with the named tables dropped, so every test starts empty. */
    static Sql fresh(String... tables) {
        Sql sql = Sql.of(requirePostgres());
        for (String t : tables) sql.execute("DROP TABLE IF EXISTS " + t);
        return sql;
    }
}
