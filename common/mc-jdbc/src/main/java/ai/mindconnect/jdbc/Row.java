package ai.mindconnect.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The current row of a result set, read by column name and typed the way the
 * domain wants it: {@link UUID}, {@link Instant}, enums, documents. Nullable
 * columns come back as {@code null}, never as a zero.
 */
public final class Row {

    private final ResultSet rs;
    private final Json json;

    Row(ResultSet rs, Json json) {
        this.rs = rs;
        this.json = json;
    }

    public String string(String column) throws SQLException {
        return rs.getString(column);
    }

    public UUID uuid(String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    public Instant instant(String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    public Integer integer(String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }

    public Long longValue(String column) throws SQLException {
        return rs.getObject(column, Long.class);
    }

    public Boolean bool(String column) throws SQLException {
        return rs.getObject(column, Boolean.class);
    }

    public <E extends Enum<E>> E enumValue(String column, Class<E> type) throws SQLException {
        String name = rs.getString(column);
        return name == null ? null : Enum.valueOf(type, name);
    }

    /** A {@code bytea} column, whole. For content too large to hold, use {@link #raw()} and {@code getBinaryStream}. */
    public byte[] bytes(String column) throws SQLException {
        return rs.getBytes(column);
    }

    /** A {@code jsonb} (or {@code text}) column deserialized as {@code type}. */
    public <T> T json(String column, Class<T> type) throws SQLException {
        return json.read(rs.getString(column), type);
    }

    public <T> T json(String column, TypeReference<T> type) throws SQLException {
        return json.read(rs.getString(column), type);
    }

    /** The result set itself, for the one column type this class did not think of. */
    public ResultSet raw() {
        return rs;
    }
}
