package ai.mindconnect.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Statements against a {@link DataSource}, without the ceremony: one
 * connection per call, parameters bound by Java type, rows mapped through a
 * {@link RowMapper}, {@link SQLException} turned into {@link JdbcException}.
 *
 * <p>Parameters are bound as they come, with four conveniences on top of
 * {@code setObject}: {@link UUID} stays a uuid, {@link Instant} becomes a
 * {@code timestamptz}, an enum is stored by name, and a {@link Jsonb} lands
 * as {@code jsonb} without a cast in the statement.
 *
 * <p>{@link #inTransaction} hands the work a {@code Sql} that is pinned to one
 * connection; everything run through it commits or rolls back together.
 * Calling it from inside a transaction joins the outer one.
 */
public final class  Sql {

    private static final Logger log = LoggerFactory.getLogger(Sql.class);

    private final DataSource dataSource;
    private final Connection pinned;
    private final Json json;

    private Sql(DataSource dataSource, Connection pinned, Json json) {
        this.dataSource = dataSource;
        this.pinned = pinned;
        this.json = json;
    }

    public static Sql of(DataSource dataSource) {
        return of(dataSource, Json.defaults());
    }

    /** Use the application's mapper, so the stored documents match what the app serializes elsewhere. */
    public static Sql of(DataSource dataSource, Json json) {
        return new Sql(dataSource, null, json);
    }

    public Json json() {
        return json;
    }

    // ── reading ─────────────────────────────────────────────────────────────

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    Row row = new Row(rs, json);
                    while (rs.next()) {
                        out.add(mapper.map(row));
                    }
                    return out;
                }
            }
        });
    }

    /** At most one row. More than one is a bug in the query, and says so. */
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        if (rows.size() > 1) {
            throw new JdbcException("Expected at most one row, got " + rows.size() + ": " + sql);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** A single-column, single-row value — {@code SELECT count(*) …} and friends. */
    public <T> T scalar(String sql, Class<T> type, Object... params) {
        return queryOne(sql, row -> row.raw().getObject(1, type), params).orElse(null);
    }

    // ── writing ─────────────────────────────────────────────────────────────

    /** INSERT, UPDATE or DELETE; returns the affected row count. */
    public int update(String sql, Object... params) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** DDL or any other statement without parameters; several statements separated by {@code ;} are fine. */
    public void execute(String sql) {
        withConnection(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    /** {@link #execute} the classpath resource next to {@code owner}, typically a {@code schema-*.sql}. */
    public void executeResource(Class<?> owner, String resource) {
        try (InputStream in = owner.getResourceAsStream(resource)) {
            if (in == null) throw new JdbcException("Resource missing: " + resource + " next to " + owner.getName());
            execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JdbcException("Cannot read " + resource + ": " + e.getMessage(), e);
        }
    }

    // ── transactions ────────────────────────────────────────────────────────

    public <T> T inTransaction(Function<Sql, T> work) {
        if (pinned != null) {
            return work.apply(this);
        }
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(new Sql(null, c, json));
                c.commit();
                return result;
            } catch (RuntimeException | Error e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new JdbcException("Transaction failed: " + e.getMessage(), e);
        }
    }

    public void inTransaction(Consumer<Sql> work) {
        inTransaction(sql -> {
            work.accept(sql);
            return null;
        });
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("Rollback failed: {}", e.toString());
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T withConnection(Work<T> work) {
        try {
            if (pinned != null) {
                return work.run(pinned);
            }
            try (Connection c = dataSource.getConnection()) {
                return work.run(c);
            }
        } catch (SQLException e) {
            throw new JdbcException(e.getMessage(), e);
        }
    }

    static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            bind(ps, i + 1, params[i]);
        }
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        switch (value) {
            case null -> ps.setNull(index, Types.OTHER);
            case Jsonb j -> ps.setObject(index, j.json(), Types.OTHER);
            case Instant t -> ps.setObject(index, t.atOffset(ZoneOffset.UTC));
            case Enum<?> e -> ps.setString(index, e.name());
            default -> ps.setObject(index, value);
        }
    }
}
