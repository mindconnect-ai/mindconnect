package ai.mindconnect.channel.jdbc;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Postgres-backed {@link ChannelStore}: the store assigns the seq — an atomic
 * increment on the channel's head row — so there is ONE gapless seq space per
 * channel across every publishing process. A subscriber on any node replays
 * from here ({@code readAfter}) and mirrors live events into its local
 * {@link Channel} via {@code publishAt}, which is the whole cluster story of
 * the observation plane.
 */
public final class JdbcChannelStore<E> implements ChannelStore<E> {

    private final DataSource dataSource;
    private final Class<E> type;
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public JdbcChannelStore(DataSource dataSource, Class<E> type) {
        this.dataSource = dataSource;
        this.type = type;
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public JdbcChannelStore<E> initSchema() {
        try (InputStream in = JdbcChannelStore.class.getResourceAsStream(
                "/ai/mindconnect/taskqueue/jdbc/schema-channel-postgres.sql")) {
            if (in == null) throw new IllegalStateException("channel schema resource missing");
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = dataSource.getConnection();
                 var statement = c.createStatement()) {
                statement.execute(ddl);
            }
            return this;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot run channel schema: " + e.getMessage());
        }
    }

    @Override
    public long append(String channelId, E value) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long seq;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mc_channel (id, last_seq) VALUES (?, 1) "
                        + "ON CONFLICT (id) DO UPDATE SET last_seq = mc_channel.last_seq + 1 "
                        + "RETURNING last_seq")) {
                    ps.setString(1, channelId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        seq = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mc_channel_event (channel_id, seq, payload) VALUES (?, ?, ?::jsonb)")) {
                    ps.setString(1, channelId);
                    ps.setLong(2, seq);
                    ps.setString(3, mapper.writeValueAsString(value));
                    ps.executeUpdate();
                }
                c.commit();
                return seq;
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Channel append failed: " + e.getMessage());
        }
    }

    @Override
    public List<Channel.Event<E>> readAfter(String channelId, long afterSeq, int limit) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq, payload FROM mc_channel_event "
                     + "WHERE channel_id = ? AND seq > ? ORDER BY seq LIMIT ?")) {
            ps.setString(1, channelId);
            ps.setLong(2, afterSeq);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Channel.Event<E>> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new Channel.Event<>(rs.getLong(1),
                            mapper.readValue(rs.getString(2), type)));
                }
                return events;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Channel read failed: " + e.getMessage());
        }
    }

    @Override
    public int purgeBefore(String channelId, long beforeSeq) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM mc_channel_event WHERE channel_id = ? AND seq < ?")) {
            ps.setString(1, channelId);
            ps.setLong(2, beforeSeq);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Channel purge failed: " + e.getMessage());
        }
    }

    @Override
    public int purgeOlderThan(String channelId, java.time.Instant before) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM mc_channel_event WHERE channel_id = ? AND at < ?")) {
            ps.setString(1, channelId);
            ps.setObject(2, before.atOffset(java.time.ZoneOffset.UTC));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Channel purge failed: " + e.getMessage());
        }
    }

    @Override
    public long lastSeq(String channelId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_seq FROM mc_channel WHERE id = ?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Channel lastSeq failed: " + e.getMessage());
        }
    }
}
