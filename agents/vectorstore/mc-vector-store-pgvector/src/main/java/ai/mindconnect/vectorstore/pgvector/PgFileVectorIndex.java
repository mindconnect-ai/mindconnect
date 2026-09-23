package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.fileindex.FileIndexChecks;
import ai.mindconnect.vectorstore.fileindex.FileVectorIndex;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link FileVectorIndex} on pgvector: the chunks of every file of every
 * namespace in the one table {@value #TABLE}, bound to one namespace per
 * instance.
 *
 * <p><b>Search is filter first, rank second.</b> The chunks of the given
 * files are selected in a {@code MATERIALIZED} CTE over the primary key
 * ({@code namespace, file_id, embedding_model, …}); only then is the cosine
 * distance computed, exactly, over those candidates. There is deliberately no
 * HNSW index: with one, the planner may search the whole table's nearest
 * neighbours and filter afterwards, returning too few hits — or none — for
 * files that are a small share of the table. An exact scan over the chunks of
 * a pool or a session is cheap up to some tens of thousands of chunks.
 *
 * <p>The {@code embedding} column has no fixed dimension, so files embedded
 * with different models share the table; {@code dimension} is stored beside it
 * and a search compares only chunks of the query's dimension.
 *
 * <p>The table is created on first use. Deleting a namespace needs nothing
 * extra: the namespace purge clears every table with a {@code namespace} column.
 */
public final class PgFileVectorIndex implements FileVectorIndex {

    static final String TABLE = "mc_file_chunk";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Serialises concurrent first uses across JVMs; any constant key will do. */
    private static final long SCHEMA_LOCK = 0x6d63_6663_6875_6e6bL;

    private final DataSource dataSource;
    private final String namespace;

    private volatile boolean schemaReady;

    public PgFileVectorIndex(DataSource dataSource, Namespace namespace) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.namespace = Objects.requireNonNull(namespace, "namespace").value();
    }

    @Override
    public void replaceFile(FileId file, String embeddingModel, List<VectorChunk> chunks) {
        FileIndexChecks.check(file, embeddingModel, chunks);
        String insert = "INSERT INTO " + TABLE
                + " (namespace, file_id, embedding_model, chunk_id, ordinal, content, meta, dimension, embedding)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::vector)";
        try (Connection connection = connect()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM " + TABLE + " WHERE namespace = ? AND file_id = ? AND embedding_model = ?");
                 PreparedStatement statement = connection.prepareStatement(insert)) {
                delete.setString(1, namespace);
                delete.setString(2, file.value());
                delete.setString(3, embeddingModel);
                delete.executeUpdate();
                for (VectorChunk chunk : chunks) {
                    statement.setString(1, namespace);
                    statement.setString(2, file.value());
                    statement.setString(3, embeddingModel);
                    statement.setString(4, chunk.id());
                    statement.setInt(5, chunk.ordinal());
                    statement.setString(6, chunk.text());
                    statement.setString(7, MAPPER.writeValueAsString(chunk.metadata()));
                    statement.setInt(8, chunk.embedding().length);
                    statement.setString(9, PgVectorStore.vectorLiteral(chunk.embedding()));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            throw failure("index file '" + file + "' with '" + embeddingModel + "'", e);
        }
    }

    @Override
    public void deleteFile(FileId file) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE namespace = ? AND file_id = ?")) {
            statement.setString(1, namespace);
            statement.setString(2, file.value());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("delete file '" + file + "'", e);
        }
    }

    @Override
    public long chunkCount(FileId file, String embeddingModel) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + TABLE
                             + " WHERE namespace = ? AND file_id = ? AND embedding_model = ?")) {
            statement.setString(1, namespace);
            statement.setString(2, file.value());
            statement.setString(3, embeddingModel);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw failure("count chunks of file '" + file + "'", e);
        }
    }

    @Override
    public List<FileHit> search(Set<FileId> files, String embeddingModel, float[] queryEmbedding, int topK,
                                Map<String, String> metadata) {
        FileIndexChecks.requireModel(embeddingModel);
        if (files.isEmpty() || topK <= 0) {
            return List.of();
        }
        boolean byMetadata = metadata != null && !metadata.isEmpty();
        String sql = "WITH candidates AS MATERIALIZED ("
                + " SELECT file_id, chunk_id, ordinal, content, meta, embedding FROM " + TABLE
                + " WHERE namespace = ? AND file_id = ANY(?) AND embedding_model = ? AND dimension = ?"
                + (byMetadata ? " AND meta @> ?::jsonb" : "")
                + ") SELECT file_id, chunk_id, ordinal, content, meta, embedding <=> ?::vector AS distance"
                + " FROM candidates ORDER BY distance LIMIT ?";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, namespace);
            statement.setArray(index++, connection.createArrayOf("text",
                    files.stream().map(FileId::value).toArray()));
            statement.setString(index++, embeddingModel);
            statement.setInt(index++, queryEmbedding.length);
            if (byMetadata) {
                statement.setString(index++, MAPPER.writeValueAsString(metadata));
            }
            statement.setString(index++, PgVectorStore.vectorLiteral(queryEmbedding));
            statement.setInt(index, topK);
            try (ResultSet rs = statement.executeQuery()) {
                List<FileHit> hits = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> meta = MAPPER.readValue(rs.getString("meta"), new TypeReference<>() {});
                    String fileId = rs.getString("file_id");
                    // Embeddings are not read back — the searcher needs text
                    // and provenance, not the vector.
                    VectorChunk chunk = new VectorChunk(rs.getString("chunk_id"), fileId,
                            rs.getInt("ordinal"), rs.getString("content"), meta, new float[0]);
                    hits.add(new FileHit(FileId.of(fileId), chunk, 1 - rs.getDouble("distance")));
                }
                return hits;
            }
        } catch (Exception e) {
            throw failure("search in " + files.size() + " files", e);
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private Connection connect() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (!schemaReady) {
            try {
                ensureSchema(connection);
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
        }
        return connection;
    }

    private synchronized void ensureSchema(Connection connection) throws SQLException {
        if (schemaReady) {
            return;
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            // Concurrent CREATE ... IF NOT EXISTS can still collide in the
            // catalogue; the transaction-scoped lock makes first use serial.
            statement.execute("SELECT pg_advisory_xact_lock(" + SCHEMA_LOCK + ")");
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + " namespace text NOT NULL,"
                    + " file_id text NOT NULL,"
                    + " embedding_model text NOT NULL,"
                    + " chunk_id text NOT NULL,"
                    + " ordinal int NOT NULL,"
                    + " content text NOT NULL,"
                    + " meta jsonb NOT NULL DEFAULT '{}',"
                    + " dimension int NOT NULL,"
                    + " embedding vector NOT NULL,"
                    + " created_at timestamptz NOT NULL DEFAULT now(),"
                    + " PRIMARY KEY (namespace, file_id, embedding_model, chunk_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_meta_idx ON " + TABLE
                    + " USING gin (meta jsonb_path_ops)");
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
        schemaReady = true;
    }

    private IllegalStateException failure(String what, Exception e) {
        return new IllegalStateException("pgvector file index (namespace '" + namespace + "'): "
                + what + " failed: " + e.getMessage(), e);
    }
}
