package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One pgvector-backed store. The table is created lazily on the first upsert,
 * because that is when the embedding dimension is known; searches against a
 * store that never saw data simply return nothing. Cosine distance
 * ({@code <=>}) with an HNSW index; score = 1 - distance, matching the
 * memory backend's cosine similarity.
 *
 * <p>Connections come from the given {@link DataSource} — a Spring Boot host
 * passes its auto-configured (Hikari) pool, the config-driven
 * {@link PgVectorBackend} builds an unpooled {@code PGSimpleDataSource}.
 */
public final class PgVectorStore implements VectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String table;
    private final DataSource dataSource;

    private volatile boolean tableReady;

    public PgVectorStore(DataSource dataSource, String id) {
        this.id = id;
        this.table = "vs_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        this.dataSource = dataSource;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void upsert(List<VectorChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        int dimension = chunks.get(0).embedding().length;
        try (Connection connection = connect()) {
            ensureTable(connection, dimension);
            String sql = "INSERT INTO " + table
                    + " (chunk_id, file_id, ordinal, content, metadata, embedding)"
                    + " VALUES (?, ?, ?, ?, ?::jsonb, ?::vector)"
                    + " ON CONFLICT (chunk_id) DO UPDATE SET file_id = EXCLUDED.file_id,"
                    + " ordinal = EXCLUDED.ordinal, content = EXCLUDED.content,"
                    + " metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (VectorChunk chunk : chunks) {
                    if (chunk.embedding().length != dimension) {
                        throw new IllegalArgumentException("Chunk '" + chunk.id() + "' has dimension "
                                + chunk.embedding().length + ", batch started with " + dimension);
                    }
                    statement.setString(1, chunk.id());
                    statement.setString(2, chunk.fileId());
                    statement.setInt(3, chunk.ordinal());
                    statement.setString(4, chunk.text());
                    statement.setString(5, MAPPER.writeValueAsString(chunk.metadata()));
                    statement.setString(6, vectorLiteral(chunk.embedding()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        } catch (Exception e) {
            throw new IllegalStateException("pgvector upsert failed for store '" + id + "': " + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchHit> search(float[] queryEmbedding, int topK) {
        try (Connection connection = connect()) {
            if (!tableExists(connection)) {
                return List.of();
            }
            String sql = "SELECT chunk_id, file_id, ordinal, content, metadata,"
                    + " 1 - (embedding <=> ?::vector) AS score"
                    + " FROM " + table
                    + " ORDER BY embedding <=> ?::vector LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                String literal = vectorLiteral(queryEmbedding);
                statement.setString(1, literal);
                statement.setString(2, literal);
                statement.setInt(3, topK);
                try (ResultSet rs = statement.executeQuery()) {
                    List<SearchHit> hits = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, String> metadata = MAPPER.readValue(
                                rs.getString("metadata"), new TypeReference<>() {});
                        // Embeddings are not read back — the searcher needs text
                        // and provenance, not the vector.
                        hits.add(new SearchHit(new VectorChunk(
                                rs.getString("chunk_id"), rs.getString("file_id"),
                                rs.getInt("ordinal"), rs.getString("content"),
                                metadata, new float[0]), rs.getDouble("score")));
                    }
                    return hits;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("pgvector search failed for store '" + id + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String fileId) {
        try (Connection connection = connect()) {
            if (!tableExists(connection)) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE file_id = ?")) {
                statement.setString(1, fileId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector delete failed for store '" + id + "': " + e.getMessage(), e);
        }
    }

    @Override
    public long chunkCount() {
        try (Connection connection = connect()) {
            if (!tableExists(connection)) {
                return 0;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector count failed for store '" + id + "': " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Long> listFiles() {
        try (Connection connection = connect()) {
            if (!tableExists(connection)) {
                return Map.of();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT file_id, count(*) AS n FROM " + table + " GROUP BY file_id ORDER BY file_id")) {
                Map<String, Long> files = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    files.put(rs.getString("file_id"), rs.getLong("n"));
                }
                return files;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector listFiles failed for store '" + id + "': " + e.getMessage(), e);
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    private void ensureTable(Connection connection, int dimension) throws SQLException {
        if (tableReady) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " chunk_id text PRIMARY KEY,"
                    + " file_id text NOT NULL,"
                    + " ordinal int NOT NULL,"
                    + " content text NOT NULL,"
                    + " metadata jsonb NOT NULL DEFAULT '{}',"
                    + " embedding vector(" + dimension + ") NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_file_idx ON " + table + " (file_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table
                    + " USING hnsw (embedding vector_cosine_ops)");
        }
        tableReady = true;
    }

    private boolean tableExists(Connection connection) throws SQLException {
        if (tableReady) {
            return true;
        }
        try (ResultSet rs = connection.getMetaData().getTables(null, null, table, null)) {
            tableReady = rs.next();
            return tableReady;
        }
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
