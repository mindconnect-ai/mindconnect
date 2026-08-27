package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.vectorstore.VectorStore;
import ai.mindconnect.vectorstore.VectorStoreBackend;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pgvector backend: each store is one table ({@code vs_<storeId>}) with an
 * HNSW cosine index, so different stores can carry different embedding
 * dimensions.
 *
 * <p>This config-driven SPI path builds one unpooled
 * {@link PGSimpleDataSource} per url+user (cached), which keeps the module
 * free of pool dependencies. A host that wants pooling constructs
 * {@link PgVectorStore} directly with its own {@link DataSource} — in a
 * Spring Boot app that is the auto-configured Hikari pool.
 *
 * <p>Config keys:
 * <ul>
 *   <li>{@code url} — JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/mindconnect} (required)</li>
 *   <li>{@code user} / {@code password} — credentials (optional if in the URL)</li>
 * </ul>
 *
 * <p>The {@code vector} extension must be installed in the database; the
 * backend runs {@code CREATE EXTENSION IF NOT EXISTS vector} on first use and
 * degrades with a clear error if it lacks the privilege.
 */
public final class PgVectorBackend implements VectorStoreBackend {

    /** One DataSource per url+user — PGSimpleDataSource is stateless, sharing is safe. */
    private static final Map<String, DataSource> DATA_SOURCES = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "pgvector";
    }

    @Override
    public VectorStore open(String storeId, Map<String, String> config) {
        return new PgVectorStore(dataSource(config), storeId);
    }

    @Override
    public List<String> listStores(Map<String, String> config) {
        String url = config == null ? null : config.get("url");
        if (url == null || url.isBlank()) {
            return List.of();
        }
        List<String> stores = new java.util.ArrayList<>();
        try (var connection = dataSource(config).getConnection();
             var rs = connection.getMetaData().getTables(null, null, "vs_%", new String[]{"TABLE"})) {
            while (rs.next()) {
                stores.add(rs.getString("TABLE_NAME").substring(3));
            }
        } catch (java.sql.SQLException e) {
            return List.of();
        }
        java.util.Collections.sort(stores);
        return stores;
    }

    private static DataSource dataSource(Map<String, String> config) {
        String url = config == null ? null : config.get("url");
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
