package ai.mindconnect.taskqueue.jdbc;

import ai.mindconnect.taskqueue.SharedStateStore;
import ai.mindconnect.taskqueue.TaskQueueException;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Postgres-backed {@link SharedStateStore}. The claim's atomicity is
 * {@code INSERT … ON CONFLICT DO NOTHING}: of any number of concurrent
 * claimers — across every node — exactly the one whose insert lands answers
 * {@code true}. Values are stored as JSONB, read back as plain JSON values.
 */
public final class JdbcSharedStateStore implements SharedStateStore {

    private final DataSource dataSource;

    public JdbcSharedStateStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public JdbcSharedStateStore initSchema() {
        try (var in = JdbcSharedStateStore.class.getResourceAsStream("schema-shared-state-postgres.sql")) {
            if (in == null) throw new TaskQueueException("shared-state schema resource missing");
            String ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try (var c = dataSource.getConnection(); var statement = c.createStatement()) {
                statement.execute(ddl);
            }
            return this;
        } catch (java.io.IOException | SQLException e) {
            throw new TaskQueueException("Cannot run shared-state schema: " + e.getMessage());
        }
    }

    @Override
    public boolean putIfAbsent(String id, String key, Object value) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mc_shared_state (id, key, value) VALUES (?,?,?::jsonb) "
                    + "ON CONFLICT DO NOTHING")) {
                ps.setString(1, id);
                ps.setString(2, key);
                ps.setString(3, Json.write(value));
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void put(String id, String key, Object value) {
        execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mc_shared_state (id, key, value) VALUES (?,?,?::jsonb) "
                    + "ON CONFLICT (id, key) DO UPDATE SET value = EXCLUDED.value")) {
                ps.setString(1, id);
                ps.setString(2, key);
                ps.setString(3, Json.write(value));
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<Object> get(String id, String key) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value FROM mc_shared_state WHERE id = ? AND key = ?")) {
                ps.setString(1, id);
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(Json.readValue(rs.getString(1))) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Map<String, Object> all(String id) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT key, value FROM mc_shared_state WHERE id = ? ORDER BY key")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Object> all = new LinkedHashMap<>();
                    while (rs.next()) all.put(rs.getString(1), Json.readValue(rs.getString(2)));
                    return all;
                }
            }
        });
    }

    @Override
    public int clear(String id) {
        return execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM mc_shared_state WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(java.sql.Connection c) throws SQLException;
    }

    private <T> T execute(Work<T> work) {
        try (var c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new TaskQueueException("Shared state operation failed: " + e.getMessage());
        }
    }
}
