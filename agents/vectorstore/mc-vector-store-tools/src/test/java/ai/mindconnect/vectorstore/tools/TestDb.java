package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Postgres the tests run against, or a skipped test:
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 * — a pgvector image, so the extension is there.
 *
 * <p>For a database <em>without</em> pgvector, {@link #withoutPgvector()} signs
 * in to a database of its own ({@code mc_novector}) as a role that is no
 * superuser: the {@code vector} extension is not installed there and is not a
 * trusted extension, so that role cannot create it — what a plain
 * {@code postgres:16-alpine} amounts to for the application.
 */
final class TestDb {

    static final String URL = System.getenv().getOrDefault(
            "MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test");

    private static final String NO_VECTOR = "mc_novector";

    private TestDb() {
    }

    static DataSource requirePostgres() {
        DataSource ds = dataSource(URL, USER, PASSWORD);
        try (Connection c = ds.getConnection()) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable at " + URL + " — skipping");
        }
        return ds;
    }

    /** A database without the {@code vector} extension, and a user that may not create it. */
    static DataSource withoutPgvector() {
        Sql admin = Sql.of(requirePostgres());
        if (admin.scalar("SELECT count(*) FROM pg_roles WHERE rolname = ?", Long.class, NO_VECTOR) == 0) {
            admin.execute("CREATE ROLE " + NO_VECTOR + " LOGIN NOSUPERUSER PASSWORD '" + NO_VECTOR + "'");
        }
        if (admin.scalar("SELECT count(*) FROM pg_database WHERE datname = ?", Long.class, NO_VECTOR) == 0) {
            admin.execute("CREATE DATABASE " + NO_VECTOR + " OWNER " + NO_VECTOR);
        }
        String url = URL.substring(0, URL.lastIndexOf('/') + 1) + NO_VECTOR;
        Sql.of(dataSource(url, USER, PASSWORD)).execute("DROP EXTENSION IF EXISTS vector CASCADE");
        return dataSource(url, NO_VECTOR, NO_VECTOR);
    }

    /** Removes what the vector stores of {@code namespace} keep in the database: registry rows and pgvector tables. */
    static void forget(DataSource dataSource, Namespace namespace) {
        Sql sql = Sql.of(dataSource);
        for (String table : List.of("mc_vector_store_template", "mc_vector_store_instance")) {
            if (sql.scalar("SELECT to_regclass(?)::text", String.class, table) != null) {
                sql.update("DELETE FROM " + table + " WHERE namespace = ?", namespace.value());
            }
        }
        String prefix = "vs_" + namespace.value().replaceAll("[^a-z0-9_]", "_") + "__";
        for (String table : sql.query("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND starts_with(table_name, ?)",
                row -> row.string("table_name"), prefix)) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    /**
     * What a runtime on Postgres persistence offers the vector stores: its Sql
     * and DataSource, the embedding services (a fake with fixed three-dimensional
     * vectors) and the data directory — plus whatever {@code strings} add.
     */
    static ToolEnvironment postgresEnvironment(DataSource dataSource, Path dataDir, Map<String, String> strings) {
        Sql sql = Sql.of(dataSource);
        LlmEmbeddings embeddings = (config, texts) -> texts.stream()
                .map(t -> t.contains("container") ? new float[]{1f, 0f, 0f} : new float[]{0f, 0f, 1f})
                .toList();
        LlmConfigRepository configs = new LlmConfigRepository() {
            @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
            @Override public Optional<LlmConfig> findByName(String name) {
                return Optional.of(LlmConfig.lmStudio(name, "fake-model", "http://unused"));
            }
            @Override public List<LlmConfig> findAll() { return List.of(); }
            @Override public void save(LlmConfig config) { }
            @Override public void deleteById(LlmConfigId id) { }
        };
        return new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == Sql.class) return Optional.of((T) sql);
                if (type == DataSource.class) return Optional.of((T) dataSource);
                if (type == LlmEmbeddings.class) return Optional.of((T) embeddings);
                if (type == LlmConfigRepository.class) return Optional.of((T) configs);
                return Optional.empty();
            }
            @Override public Optional<String> getString(String key) {
                if ("dataBaseDir".equals(key)) return Optional.of(dataDir.toString());
                return Optional.ofNullable(strings.get(key));
            }
        };
    }

    private static DataSource dataSource(String url, String user, String password) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }
}
