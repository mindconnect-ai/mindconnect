package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.VectorStoreBackend;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pgvector backend: each store is one table ({@code vs_<namespace>__<storeId>}) with an
 * HNSW cosine index, so different stores can carry different embedding
 * dimensions.
 *
 * <p>This config-driven SPI path builds one unpooled
 * {@link PGSimpleDataSource} per url+user (cached), which keeps the module
 * free of pool dependencies. A host that wants pooling constructs
 * {@link PgVectorStore} directly with its own {@link DataSource} — in a
 * Spring Boot app that is the auto-configured Hikari pool.
 *
 * <p>A host that keeps its own data in Postgres binds the backend to that
 * database instead — {@link #PgVectorBackend(DataSource)} — and a store
 * without a {@code url} of its own then lives there, on the host's pool.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code url} — JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/mindconnect}
 *       (required unless the backend is bound to a data source)</li>
 *   <li>{@code user} / {@code password} — credentials (optional if in the URL)</li>
 * </ul>
 *
 * <p>The {@code vector} extension must be installed in the database; the
 * backend runs {@code CREATE EXTENSION IF NOT EXISTS vector} on first use and
 * degrades with a clear error if it lacks the privilege. {@link #enableExtension}
 * asks up front.
 */
public final class PgVectorBackend implements VectorStoreBackend {

    public static final String TYPE = "pgvector";

    /** One DataSource per url+user — PGSimpleDataSource is stateless, sharing is safe. */
    private static final Map<String, DataSource> DATA_SOURCES = new ConcurrentHashMap<>();

    /** Where a store without a {@code url} lives; null: every store names its own. */
    private final DataSource bound;

    /** The ServiceLoader's backend: every store names its database by {@code url}. */
    public PgVectorBackend() {
        this(null);
    }

    /** A backend whose stores live in {@code dataSource} unless their config names a {@code url}. */
    public PgVectorBackend(DataSource dataSource) {
        this.bound = dataSource;
    }

    /**
     * Makes sure the database has the {@code vector} extension, by
     * {@code CREATE EXTENSION IF NOT EXISTS vector}.
     *
     * @return empty when the extension is there; otherwise the database's reason —
     *         typically that the server has no pgvector at all (a plain
     *         {@code postgres} image), or that the user may not create it
     */
    public static java.util.Optional<String> enableExtension(DataSource dataSource) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            return java.util.Optional.empty();
        } catch (java.sql.SQLException e) {
            return java.util.Optional.of(e.getMessage());
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public VectorStore open(String storeId, Map<String, String> config) {
        return new PgVectorStore(dataSource(config), namespace(config), storeId);
    }

    @Override
    public List<String> listStores(Map<String, String> config) {
        String url = config == null ? null : config.get("url");
        if ((url == null || url.isBlank()) && bound == null) {
            return List.of();
        }
        String prefix = PgVectorStore.tablePrefix(namespace(config));
        List<String> stores = new java.util.ArrayList<>();
        try (var connection = dataSource(config).getConnection();
             var rs = connection.getMetaData().getTables(null, null, "vs_%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                if (table.startsWith(prefix)) {
                    stores.add(table.substring(prefix.length()));
                }
            }
        } catch (java.sql.SQLException e) {
            return List.of();
        }
        java.util.Collections.sort(stores);
        return stores;
    }

    private static String namespace(Map<String, String> config) {
        String namespace = config == null ? null : config.get("namespace");
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("pgvector backend requires the 'namespace' config key");
        }
        return namespace;
    }

    private DataSource dataSource(Map<String, String> config) {
        String url = config == null ? null : config.get("url");
        if ((url == null || url.isBlank()) && bound != null) {
            return bound;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("pgvector backend requires the 'url' config key");
        }
        String user = config.get("user");
        String password = config.get("password");
        return DATA_SOURCES.computeIfAbsent(url + "|" + user, key -> {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(url);
            if (user != null && !user.isBlank()) {
                ds.setUser(user);
                ds.setPassword(password);
            }
            return ds;
        });
    }
}
